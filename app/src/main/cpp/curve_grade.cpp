#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {
float clamp(float v) { return std::clamp(v, 0.0f, 1.0f); }
float luma(float r, float g, float b) { return .2126f*r + .7152f*g + .0722f*b; }
float tone(float v) { return v <= .4614f ? .5f*v/.4614f : .5f+.5f*(v-.4614f)/(1.0f-.4614f); }
float power(float v, float e) { return float(std::pow(double(std::max(v, 0.0f)), double(e))); }
// Upstream Layer2Kernel.swift: adaptive saturation, then smoothly weighted chroma-plane HSL.
void adjustColor(float& r, float& g, float& b, const float* c) {
    if (c[0] != 0.0f) {
        const float high = std::max({r, g, b}), low = std::min({r, g, b});
        const float sat = high > 1e-4f ? clamp((high - low) / high) : 0.0f;
        const float l = luma(r, g, b), scale = std::max(1.0f + c[0] * (1.0f - sat), 0.0f);
        r = l + (r - l) * scale;
        g = l + (g - l) * scale;
        b = l + (b - l) * scale;
    }
    if (c[1] == 0.0f) return;
    const float l = luma(r, g, b), dr = r - l, dg = g - l, db = b - l;
    const float x = dr - .5f * (dg + db), y = .8660254f * (dg - db);
    const float chroma = std::sqrt(x * x + y * y);
    if (chroma < 1e-6f) return;
    float hue = std::atan2(y, x) * 57.29577951f;
    if (hue < 0.0f) hue += 360.0f;
    constexpr float centres[9] = {0, 30, 60, 120, 180, 240, 270, 300, 360};
    int i = 0;
    while (i < 7 && hue >= centres[i + 1]) ++i;
    const int next = (i + 1) % 8;
    float t = clamp((hue - centres[i]) / (centres[i + 1] - centres[i]));
    t = t * t * (3.0f - 2.0f * t);
    const auto mix = [&](int offset) { return c[offset + i] * (1.0f - t) + c[offset + next] * t; };
    const float turn = mix(2), scale = std::max(1.0f + mix(10), 0.0f);
    const float cs = std::cos(turn), sn = std::sin(turn);
    const float qx = (x * cs - y * sn) * scale, qy = (x * sn + y * cs) * scale;
    const float q = qy / .8660254f, s = -2.0f * .2126f * qx - (.7152f - .0722f) * q;
    const float weight = clamp(chroma / .2f);
    const float lift = 1.0f + .5f * mix(18) * weight * weight * (3.0f - 2.0f * weight);
    r = (l + qx + .5f * s) * lift;
    g = (l + .5f * (s + q)) * lift;
    b = (l + .5f * (s - q)) * lift;
}

float curve(const float* table, float x, int row) {
    const float at = clamp(x)*1024.0f;
    const int low = int(at), high = std::min(low+1, 1024);
    const float f = at-float(low);
    return table[row*1025+low]*(1.0f-f)+table[row*1025+high]*f;
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_spektralab_android_engine_NativeCurveGrade_prepareBrightness(
    JNIEnv* env, jobject, jfloatArray uniforms, jobject channels) {
    auto* table = static_cast<float*>(env->GetDirectBufferAddress(channels));
    if (!table || env->GetDirectBufferCapacity(channels) != 3 * 65536 * sizeof(float) ||
        env->GetArrayLength(uniforms) != 25) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid brightness table");
        return;
    }
    float p[25];
    env->GetFloatArrayRegion(uniforms, 0, 25, p);
    if (env->ExceptionCheck()) return;
    // Enumerate all U16 samples with the original operations and double-precision power.
    // This is an exact discrete memoization, never a sampled/interpolated colour LUT.
    for (int channel = 0; channel < 3; ++channel) {
        for (int sample = 0; sample < 65536; ++sample) {
            float value = float(sample) / 65535.0f;
            value *= p[channel];
            if (p[3] != 1.0f) value *= p[3];
            if (p[4] != 0.0f) value = (value - .4614f) * (1.0f + p[4] * 1.2f) + .4614f;
            if (p[5] != 0.0f) value = power(value, 1.0f / (1.0f + p[5] * .8f));
            table[channel * 65536 + sample] = value;
        }
    }
}

namespace {
void applyStrip(JNIEnv* env,
    jshortArray input, jint offset, jint width, jint height, jint y, jint rows,
    jfloatArray uniforms, jfloatArray curves, jfloatArray colors, jfloatArray vignette, jobject brightness, jarray output, jintArray histogram, bool packed, bool scalarTone) {
    const auto* channels = brightness ? static_cast<const float*>(env->GetDirectBufferAddress(brightness)) : nullptr;
    const int64_t count = int64_t(width)*rows;
    const auto color_count = env->GetArrayLength(colors);
    if (width <= 0 || height <= 0 || y < 0 || rows <= 0 || rows > 32 || int64_t(y)+rows > height ||
        count > 1024*1024 || offset < 0 || int64_t(offset)+count*4 > env->GetArrayLength(input) ||
        env->GetArrayLength(output) < count*(packed ? 1 : 3) ||
        (packed && (!histogram || env->GetArrayLength(histogram) != 768)) || env->GetArrayLength(uniforms) != 25 ||
        env->GetArrayLength(curves) != 5125 || (color_count != 26 && color_count != 36 && color_count != 53) ||
        (brightness && (!channels || env->GetDirectBufferCapacity(brightness) != 3 * 65536 * sizeof(float))) ||
        (vignette && env->GetArrayLength(vignette) != 1025)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid curve strip"); return;
    }
    try {
        std::vector<jshort> pixels(size_t(count)*4);
        std::vector<float> result(packed ? 0 : size_t(count)*3), table(5125), gains(vignette ? 1025 : 0);
        std::vector<jint> argb(packed ? size_t(count) : 0);
        jint bins[768]{};
        if (packed) env->GetIntArrayRegion(histogram, 0, 768, bins);
        float p[25], color[53]{};
        env->GetShortArrayRegion(input, offset, jsize(count*4), pixels.data());
        env->GetFloatArrayRegion(uniforms, 0, 25, p);
        env->GetFloatArrayRegion(curves, 0, 5125, table.data());
        env->GetFloatArrayRegion(colors, 0, color_count, color);
        if (vignette) env->GetFloatArrayRegion(vignette, 0, 1025, gains.data());
        if (env->ExceptionCheck()) return;
        for (int64_t i=0; i<count; ++i) {
            float r=float(uint16_t(pixels[i*4]))/65535.0f;
            float g=float(uint16_t(pixels[i*4+1]))/65535.0f;
            float b=float(uint16_t(pixels[i*4+2]))/65535.0f;
            if (p[24] == 0.0f) {
                if (channels) {
                    r = channels[uint16_t(pixels[i*4])];
                    g = channels[65536 + uint16_t(pixels[i*4+1])];
                    b = channels[131072 + uint16_t(pixels[i*4+2])];
                } else {
                r*=p[0]; g*=p[1]; b*=p[2];
                if (p[3]!=1.0f) { r*=p[3];g*=p[3];b*=p[3]; }
                if (p[4]!=0.0f) { float k=1.0f+p[4]*1.2f; r=(r-.4614f)*k+.4614f; g=(g-.4614f)*k+.4614f; b=(b-.4614f)*k+.4614f; }
                if (p[5]!=0.0f) { float e=1.0f/(1.0f+p[5]*.8f);r=power(r,e);g=power(g,e);b=power(b,e); }
                }
                const float t=tone(luma(r,g,b));
                if (p[8]!=0.0f) { float w=(1.0f-t)*(1.0f-t);r+=p[8]*.25f*w*(1.0f-r);g+=p[8]*.25f*w*(1.0f-g);b+=p[8]*.25f*w*(1.0f-b); }
                if (p[7]!=0.0f) { float k=scalarTone ? p[7]*.25f*(t*t) : p[7]*.25f*t*t; r+=k*(p[7]>0.0f?1.0f-r:r);g+=k*(p[7]>0.0f?1.0f-g:g);b+=k*(p[7]>0.0f?1.0f-b:b); }
                const float span=std::max(1.0f-p[9]-p[10],.05f);
                r=(r-p[9])/span;g=(g-p[9])/span;b=(b-p[9])/span;
                float lum=luma(r,g,b); r=lum+(r-lum)*p[6];g=lum+(g-lum)*p[6];b=lum+(b-lum)*p[6];
                if (color[0] != 0.0f || color[1] != 0.0f) {
                    adjustColor(r, g, b, color);
                    lum = luma(r, g, b);
                }
                if (color[26] != 0.0f) {
                    const float mr = color[27]*r + color[28]*g + color[29]*b;
                    const float mg = color[30]*r + color[31]*g + color[32]*b;
                    const float mb = color[33]*r + color[34]*g + color[35]*b;
                    r = mr; g = mg; b = mb;
                    lum = luma(r, g, b);
                }
                const float ls=clamp(tone(lum)), ws=(1.0f-ls)*(1.0f-ls), wh=ls*ls, wm=std::max(1.0f-ws-wh,0.0f);
                if (color[36] != 0.0f) {
                    r=r+p[11]*ws+p[14]*wm+p[17]*wh;
                    g=g+p[12]*ws+p[15]*wm+p[18]*wh;
                    b=b+p[13]*ws+p[16]*wm+p[19]*wh;
                    const float gain=1.0f+color[49]+color[50]*ws+color[51]*wm+color[52]*wh;
                    r=clamp((r+color[37]+color[40]*ws+color[43]*wm+color[46]*wh)*gain);
                    g=clamp((g+color[38]+color[41]*ws+color[44]*wm+color[47]*wh)*gain);
                    b=clamp((b+color[39]+color[42]*ws+color[45]*wm+color[48]*wh)*gain);
                } else {
                    r=clamp(r+p[11]*ws+p[14]*wm+p[17]*wh);
                    g=clamp(g+p[12]*ws+p[15]*wm+p[18]*wh);
                    b=clamp(b+p[13]*ws+p[16]*wm+p[19]*wh);
                }
            }
            if (p[23] != 0.0f) {
            const float lum=luma(r,g,b), mapped=curve(table.data(),lum,1);
            const float gain=lum>1e-4f?mapped/lum:1.0f;
            r=curve(table.data(),clamp(r*gain),0);g=curve(table.data(),clamp(g*gain),0);b=curve(table.data(),clamp(b*gain),0);
            r=curve(table.data(),r,2);g=curve(table.data(),g,3);b=curve(table.data(),b,4);
            }
            if (!gains.empty()) {
                const float nx=(2.0f*float(i%width)+1.0f)/float(width)-1.0f;
                const float ny=(2.0f*float(y+i/width)+1.0f)/float(height)-1.0f;
                const float inner=.08f+clamp(p[21])*.85f, span=.08f+clamp(p[22])*1.7f;
                const float t=clamp((nx*nx+ny*ny-inner*inner)/span);
                const float v=gains[size_t(t*1024.0f+.5f)];r*=v;g*=v;b*=v;
            }
            if (packed) {
                // Match Kotlin Float -> Int quantization; no transfer-function or precision change.
                const auto quantize = [](float value) {
                    // Kotlin Float.toInt maps NaN to zero. Never use it as a histogram index.
                    if (!(value > 0.0f)) return 0;
                    if (value >= 1.0f) return 255;
                    return int(value*255.0f+.5f);
                };
                const int qr=quantize(r), qg=quantize(g), qb=quantize(b);
                argb[i]=jint(0xff000000u | (uint32_t(qr)<<16) | (uint32_t(qg)<<8) | uint32_t(qb));
                ++bins[qr]; ++bins[256+qg]; ++bins[512+qb];
            } else {
                result[i*3]=r;result[i*3+1]=g;result[i*3+2]=b;
            }
        }
        if (packed) {
            env->SetIntArrayRegion(static_cast<jintArray>(output), 0, jsize(count), argb.data());
            if (!env->ExceptionCheck()) env->SetIntArrayRegion(histogram, 0, 768, bins);
        } else env->SetFloatArrayRegion(static_cast<jfloatArray>(output), 0, jsize(count*3), result.data());
    } catch (const std::bad_alloc&) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Curve strip allocation failed");
    }
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_spektralab_android_engine_NativeCurveGrade_apply(JNIEnv* env, jobject,
    jshortArray input, jint offset, jint width, jint height, jint y, jint rows,
    jfloatArray uniforms, jfloatArray curves, jfloatArray colors, jfloatArray vignette,
    jobject brightness, jfloatArray output) {
    applyStrip(env, input, offset, width, height, y, rows, uniforms, curves, colors, vignette,
        brightness, output, nullptr, false, false);
}

extern "C" JNIEXPORT void JNICALL
Java_com_spektralab_android_engine_NativeCurveGrade_applyArgb(JNIEnv* env, jobject,
    jshortArray input, jint offset, jint width, jint height, jint y, jint rows,
    jfloatArray uniforms, jfloatArray curves, jfloatArray colors, jfloatArray vignette,
    jobject brightness, jintArray output, jintArray histogram, jboolean scalarTone) {
    applyStrip(env, input, offset, width, height, y, rows, uniforms, curves, colors, vignette,
        brightness, output, histogram, true, scalarTone);
}
