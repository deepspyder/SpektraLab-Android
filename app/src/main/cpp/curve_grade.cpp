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
Java_com_spektralab_android_engine_NativeCurveGrade_apply(JNIEnv* env, jobject,
    jshortArray input, jint offset, jint width, jint height, jint y, jint rows,
    jfloatArray uniforms, jfloatArray curves, jfloatArray colors, jfloatArray vignette, jfloatArray output) {
    const int64_t count = int64_t(width)*rows;
    if (width <= 0 || height <= 0 || y < 0 || rows <= 0 || rows > 32 || int64_t(y)+rows > height ||
        count > 1024*1024 || offset < 0 || int64_t(offset)+count*4 > env->GetArrayLength(input) ||
        env->GetArrayLength(output) < count*3 || env->GetArrayLength(uniforms) != 25 ||
        env->GetArrayLength(curves) != 5125 || env->GetArrayLength(colors) != 26 || (vignette && env->GetArrayLength(vignette) != 1025)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid curve strip"); return;
    }
    try {
        std::vector<jshort> pixels(size_t(count)*4);
        std::vector<float> result(size_t(count)*3), table(5125), gains(vignette ? 1025 : 0);
        float p[25], color[26];
        env->GetShortArrayRegion(input, offset, jsize(count*4), pixels.data());
        env->GetFloatArrayRegion(uniforms, 0, 25, p);
        env->GetFloatArrayRegion(curves, 0, 5125, table.data());
        env->GetFloatArrayRegion(colors, 0, 26, color);
        if (vignette) env->GetFloatArrayRegion(vignette, 0, 1025, gains.data());
        if (env->ExceptionCheck()) return;
        for (int64_t i=0; i<count; ++i) {
            float r=float(uint16_t(pixels[i*4]))/65535.0f;
            float g=float(uint16_t(pixels[i*4+1]))/65535.0f;
            float b=float(uint16_t(pixels[i*4+2]))/65535.0f;
            if (p[24] == 0.0f) {
                r*=p[0]; g*=p[1]; b*=p[2];
                if (p[3]!=1.0f) { r*=p[3];g*=p[3];b*=p[3]; }
                if (p[4]!=0.0f) { float k=1.0f+p[4]*1.2f; r=(r-.4614f)*k+.4614f; g=(g-.4614f)*k+.4614f; b=(b-.4614f)*k+.4614f; }
                if (p[5]!=0.0f) { float e=1.0f/(1.0f+p[5]*.8f);r=power(r,e);g=power(g,e);b=power(b,e); }
                const float t=tone(luma(r,g,b));
                if (p[8]!=0.0f) { float w=(1.0f-t)*(1.0f-t);r+=p[8]*.25f*w*(1.0f-r);g+=p[8]*.25f*w*(1.0f-g);b+=p[8]*.25f*w*(1.0f-b); }
                if (p[7]!=0.0f) { float k=p[7]*.25f*t*t; r+=k*(p[7]>0.0f?1.0f-r:r);g+=k*(p[7]>0.0f?1.0f-g:g);b+=k*(p[7]>0.0f?1.0f-b:b); }
                const float span=std::max(1.0f-p[9]-p[10],.05f);
                r=(r-p[9])/span;g=(g-p[9])/span;b=(b-p[9])/span;
                float lum=luma(r,g,b); r=lum+(r-lum)*p[6];g=lum+(g-lum)*p[6];b=lum+(b-lum)*p[6];
                if (color[0] != 0.0f || color[1] != 0.0f) {
                    adjustColor(r, g, b, color);
                    lum = luma(r, g, b);
                }
                const float ls=clamp(tone(lum)), ws=(1.0f-ls)*(1.0f-ls), wh=ls*ls, wm=std::max(1.0f-ws-wh,0.0f);
                r=clamp(r+p[11]*ws+p[14]*wm+p[17]*wh);
                g=clamp(g+p[12]*ws+p[15]*wm+p[18]*wh);
                b=clamp(b+p[13]*ws+p[16]*wm+p[19]*wh);
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
            result[i*3]=r;result[i*3+1]=g;result[i*3+2]=b;
        }
        env->SetFloatArrayRegion(output, 0, jsize(count*3), result.data());
    } catch (const std::bad_alloc&) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Curve strip allocation failed");
    }
}
