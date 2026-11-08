// Render the portable vector sources into bounded, transparent app resources.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/sharp');
const root=path.resolve(__dirname,'..');
const artwork=path.join(root,'design/stock-artwork');
(async()=>{
  const entries=JSON.parse(fs.readFileSync(path.join(artwork,'artwork-manifest.json'),'utf8'));
  const pngdir=path.join(artwork,'png');fs.mkdirSync(pngdir,{recursive:true});
  const stats=[];
  for(const item of entries){
    const svg=path.join(artwork,'svg',item.id+'.svg');
    const png=path.join(pngdir,item.id+'.png');
    await sharp(svg,{density:288}).resize(960,1120).png().toFile(png);
    const runtime=path.join(root,'app/src/main/res/drawable-nodpi/stock_'+item.id+'.webp');
    const provenance=Buffer.from('<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/" dc:source="design/stock-artwork/svg/'+item.id+'.svg" dc:description="Square material label remastered from user-provided P1/P2 reference; missing labels use matching family; slight paper thickness; not official packaging"/></rdf:RDF></x:xmpmeta>');
    await sharp(svg,{density:144}).resize(384,448).withXmp(provenance.toString()).webp({lossless:true,effort:6}).toFile(runtime);
    const pngRuntime=await sharp(svg,{density:144}).resize(384,448).png().toBuffer();
    stats.push({id:item.id,webpBytes:fs.statSync(runtime).size,pngBytes:pngRuntime.length,width:384,height:448});
  }
  await sharp(path.join(artwork,'stock-artwork-board.svg')).flatten({background:'#191919'}).resize(1620).png().toFile(path.join(artwork,'preview-dark.png'));
  fs.writeFileSync(path.join(artwork,'format-comparison.json'),JSON.stringify(stats,null,2));
  console.log('Assets:',stats.length,'WebP bytes:',stats.reduce((n,s)=>n+s.webpBytes,0),'Equivalent PNG bytes:',stats.reduce((n,s)=>n+s.pngBytes,0));
})().catch(e=>{console.error(e);process.exit(1)});
