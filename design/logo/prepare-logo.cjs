const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const sharp = require('sharp');

// Source pixels and traced silhouette come from the user-supplied reference.
// This is deterministic reference refinement, not generated artwork.
const dir = __dirname;
const project = path.resolve(dir, '../..');
const resources = path.join(project, 'app/src/main/res/drawable-nodpi');
const source = path.join(dir, 'source-reference.png');
const silhouette = `M434,438 L434,412 C434,399 558,400 558,412 L558,439
 C589,441 611,450 611,465 L611,486
 C691,483 796,466 867,442 C879,438 888,440 889,454
 L889,731 C889,745 885,750 870,755
 C767,782 638,776 558,773 L558,817
 C558,835 432,836 432,817 L432,805
 C407,809 381,800 381,785 L381,459 C381,446 402,439 434,438 Z`;
const origin = 'User-supplied rolled-film reference; refined and adapted for Theia with explicit permission to retain the original subject. No image-generation model used.';
const xmp = `<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/" dc:source="design/logo/source-reference.png" dc:description="${origin}"/></rdf:RDF></x:xmpmeta>`;

(async () => {
    fs.mkdirSync(resources, { recursive: true });
    const metadata = await sharp(source).metadata();
    if (metadata.width !== 1254 || metadata.height !== 1254) {
        throw new Error('This silhouette requires the original 1254 x 1254 reference.');
    }
    const normalized = await sharp(source).resize(1280, 1280).png().toBuffer();
    // The outline follows the real film/roll contours, preserving its raster material.
    const mask = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="1280"><path transform="scale(${1280 / 1254})" d="${silhouette}" fill="white"/></svg>`);
    const cutout = await sharp(normalized).ensureAlpha()
        .composite([{ input: await sharp(mask).blur(0.4).png().toBuffer(), blend: 'dest-in' }])
        .png().toBuffer();
    // Replace only the presentation tile: pure black, no grain, sheen or rim.
    const tileSubject = await sharp(cutout)
        .extract({ left: 244, top: 241, width: 796, height: 796 })
        .resize(1024, 1024, { kernel: 'lanczos3' })
        .sharpen({ sigma: 0.45, m1: 0.2, m2: 0.5 }).png().toBuffer();
    const rounded = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024"><rect width="1024" height="1024" rx="240" fill="white"/></svg>');
    const tile = await sharp({ create: { width: 1024, height: 1024, channels: 4, background: '#000000' } })
        .composite([{ input: tileSubject }, { input: rounded, blend: 'dest-in' }])
        .withXmp(xmp).png().toBuffer();
    fs.writeFileSync(path.join(dir, 'theia-logo.png'), tile);
    const cropped = await sharp(cutout)
        .extract({ left: 384, top: 405, width: 529, height: 455 })
        .resize(650, 558, { kernel: 'lanczos3' })
        .png().toBuffer();
    const foreground = await sharp({ create: { width: 768, height: 768, channels: 4, background: '#00000000' } })
        .composite([{ input: cropped, left: 59, top: 105 }])
        .withXmp(xmp).png().toBuffer();
    fs.writeFileSync(path.join(dir, 'theia-logo-foreground.png'), foreground);
    await sharp(tile).resize(512, 512).withXmp(xmp)
        .webp({ lossless: true, effort: 6 }).toFile(path.join(resources, 'theia_logo_artwork.webp'));
    await sharp(foreground).resize(512, 512).withXmp(xmp)
        .webp({ lossless: true, effort: 6 }).toFile(path.join(resources, 'theia_logo_foreground.webp'));
    const manifest = {
        source: 'source-reference.png',
        sourceSha256: crypto.createHash('sha256').update(fs.readFileSync(source)).digest('hex'),
        processing: 'Traced foreground matte, pure-black rounded background, Lanczos resize and restrained sharpening; source subject retained.',
        background: '#000000',
        generation: 'none',
        runtime: ['theia_logo_artwork.webp', 'theia_logo_foreground.webp'],
        encoding: '512 x 512 lossless WebP; source is a raster reference, resizing does not invent original detail.',
    };
    fs.writeFileSync(path.join(dir, 'provenance.json'), JSON.stringify(manifest, null, 2) + '\n');
    console.log('Prepared reference-based logo masters and lossless Android assets.');
})().catch(error => { console.error(error); process.exitCode = 1; });
