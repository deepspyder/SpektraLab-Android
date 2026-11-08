"""Remaster the user's P1/P2 stock labels as sharp, shallow-relief paper tiles.

Matching supplied panels are extracted as references. Final labels use outlined
type and clean color blocks, with just a thin paper edge and directional shadow.
No cartridge drawings, photographic backgrounds, collage borders or watermarks.
"""
import json
import sys
from html import escape
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / '.tmp/figma-font-tools'))
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'design/stock-artwork'
OUT.mkdir(parents=True, exist_ok=True)
(OUT / 'svg').mkdir(exist_ok=True)
FONTS = {
    'bold': TTFont('C:/Windows/Fonts/ariblk.ttf'),
    'condensed': TTFont('C:/Windows/Fonts/impact.ttf'),
    'regular': TTFont('C:/Windows/Fonts/arial.ttf'),
}

# id, family, brand, middle line, main number/code, identifying color, footer
FILMS = [
 ('kodak_portra_400','kodak','Kodak','PORTRA','400','#6039DA','COLOR NEGATIVE'),
 ('kodak_gold_200','kodak','Kodak','GOLD','200','#BE43AB','COLOR NEGATIVE'),
 ('fujifilm_pro_400h','fuji','FUJICOLOR','PRO','400H','#4D287B','PROFESSIONAL'),
 ('kodak_ektar_100','kodak','Kodak','EKTAR','100','#A80D26','COLOR NEGATIVE'),
 ('kodak_portra_160','kodak','Kodak','PORTRA','160','#7151BD','COLOR NEGATIVE'),
 ('kodak_portra_800','kodak','Kodak','PORTRA','800','#524099','COLOR NEGATIVE'),
 ('kodak_ultramax_400','kodak','Kodak','ULTRAMAX','400','#4F95DF','COLOR NEGATIVE'),
 ('fujifilm_c200','fuji','FUJICOLOR','','C200','#128E5A','COLOR NEGATIVE'),
 ('fujifilm_xtra_400','fuji','FUJIFILM','X-TRA','400','#C38B0A','COLOR NEGATIVE'),
 ('kodak_ektachrome_100','kodak','Kodak','EKTACHROME','100','#1D347E','REVERSAL'),
 ('fujifilm_provia_100f','fuji','FUJICHROME','PROVIA','100F','#242582','REVERSAL'),
 ('fujifilm_velvia_100','fuji','FUJICHROME','VELVIA','100','#14979C','REVERSAL'),
 ('kodak_kodachrome_64','kodak','Kodak','KODACHROME','64','#A9302D','REVERSAL'),
 ('ilford_hp5_plus_400','fuji','ILFORD','HP5 PLUS','400','#335F3D','BLACK & WHITE'),
 ('kodak_tri_x_400','kodak','Kodak','TRI-X','400','#10553B','BLACK & WHITE'),
 ('kodak_tmax_100','kodak','Kodak','T-MAX','100','#28282B','BLACK & WHITE'),
 ('fujifilm_neopan_acros_100_ii','fuji','NEOPAN','ACROS II','100','#292929','BLACK & WHITE'),
 ('kodak_vision3_50d','cine','KODAK','50D','5203','#A09B5D','VISION3 COLOR NEGATIVE'),
 ('kodak_vision3_200t','cine','KODAK','200T','5213','#9D7763','VISION3 COLOR NEGATIVE'),
 ('kodak_vision3_250d','cine','KODAK','250D','5207','#E68B09','VISION3 COLOR NEGATIVE'),
 ('kodak_vision3_500t','cine','KODAK','500T','5219','#4D7BAC','VISION3 COLOR NEGATIVE'),
 ('kodak_verita_200d','cine','KODAK','200D','5206','#BB984D','VERITA COLOR NEGATIVE'),
]
PAPERS = [
 ('fujifilm_crystal_archive_typeii','paper','FUJIFILM','CRYSTAL','ARCHIVE II','#238D5D','COLOR PHOTOGRAPHIC PAPER'),
 ('kodak_ektacolor_edge','paper','Kodak','EKTACOLOR','EDGE','#AD652F','COLOR PHOTOGRAPHIC PAPER'),
 ('kodak_endura_premier','paper','Kodak','ENDURA','PREMIER','#5D3C7E','PROFESSIONAL COLOR PAPER'),
 ('kodak_portra_endura','paper','Kodak','ENDURA','PORTRA','#7A408D','PROFESSIONAL COLOR PAPER'),
 ('kodak_supra_endura','paper','Kodak','ENDURA','SUPRA','#345A9C','PROFESSIONAL COLOR PAPER'),
 ('kodak_ultra_endura','paper','Kodak','ENDURA','ULTRA','#187D82','PROFESSIONAL COLOR PAPER'),
 ('ilford_multigrade_iv_rc','paper','ILFORD','MULTIGRADE','IV RC','#293A4B','BLACK & WHITE RC PAPER'),
 ('kodak_2383','cine','KODAK','VISION','2383','#667B52','COLOR PRINT FILM'),
 ('kodak_2393','cine','KODAK','VISION','2393','#765B70','PREMIER PRINT FILM'),
]

REFERENCE_PANELS = {
 'fujifilm_c200':('p1',1,1), 'fujifilm_xtra_400':('p1',1,0),
 'fujifilm_pro_400h':('p1',2,0), 'fujifilm_provia_100f':('p1',2,2),
 'fujifilm_velvia_100':('p1',3,1), 'fujifilm_neopan_acros_100_ii':('p1',3,2),
 'kodak_gold_200':('p2',0,1), 'kodak_ektar_100':('p2',0,3),
 'kodak_portra_400':('p2',1,3), 'kodak_ultramax_400':('p2',2,1),
 'kodak_tri_x_400':('p2',2,3), 'kodak_vision3_250d':('p2',3,1),
 'kodak_vision3_500t':('p2',3,3),
}

def txt(value, x, y, size, color, align='left', weight='bold', maxwidth=None):
    font=FONTS[weight];glyphs=font.getGlyphSet();cmap=font.getBestCmap();upm=font['head'].unitsPerEm
    names=[cmap.get(ord(c),'.notdef') for c in value]
    advance=sum(font['hmtx'][n][0] for n in names)
    sy=size/upm; sx=sy
    if maxwidth and advance*sx>maxwidth:sx=maxwidth/advance
    if align=='center':x-=advance*sx/2
    paths=[];offset=0
    for name in names:
        pen=SVGPathPen(glyphs);glyphs[name].draw(pen);data=pen.getCommands()
        if data:paths.append(f'<path transform="translate({offset} 0)" d="{data}"/>')
        offset+=font['hmtx'][name][0]
    return f'<g aria-label="{escape(value)}" fill="{color}" transform="translate({x:.3f} {y:.3f}) scale({sx:.6f} {-sy:.6f})">'+''.join(paths)+'</g>'

def label(definition):
    stock,family,brand,title,number,color,footer=definition
    cream='#F8F8F0';yellow='#F7C637';white='#FFFFFF'
    if family=='fuji':
        face=f'<rect width="200" height="200" fill="{cream}"/>'
        face+=txt(brand,100,50,32,color,'center','condensed',174)
        if title:face+=txt(title,100,80,18,color,'center','condensed',170)
        face+=txt(number,100,148 if title else 148,77 if len(number)<4 else 70,color,'center','bold',184)
        face+=f'<rect y="163" width="200" height="37" fill="{color}"/>'
        face+=txt(footer,100,187,15,cream,'center','condensed',174)
    elif family=='kodak':
        face=f'<rect width="200" height="200" fill="{color}"/><rect width="200" height="60" fill="{yellow}"/>'
        face+=txt(brand,100,51,48,'#D52519','center','bold',169)
        face+=txt(title,100,94,29,white,'center','bold',180)
        face+=txt(number,100,167,83,white,'center','bold',186)
        face+=f'<rect y="182" width="200" height="18" fill="{yellow}"/>'
    elif family=='cine':
        face=f'<rect width="200" height="200" fill="{color}"/>'
        face+=txt('ISO '+title.rstrip('DT') if stock.startswith('kodak_vision') or stock=='kodak_verita_200d' else brand,12,24,10,'#F8F3E7',weight='condensed',maxwidth=175)
        face+=txt(number,100,106,69,white,'center','bold',183)
        for yy in [120,125,130,135]:face+=f'<rect x="0" y="{yy}" width="200" height="1.4" fill="#F5F0DC"/>'
        face+=f'<rect y="144" width="200" height="56" fill="{cream}"/>'
        face+=txt(title,12,179,32,color,maxwidth=174)
        face+=txt(footer,12,193,7,color,weight='condensed',maxwidth=174)
    else:
        top=cream if brand in ('FUJIFILM','ILFORD') else yellow
        face=f'<rect width="200" height="200" fill="{cream}"/><rect width="200" height="56" fill="{top}"/>'
        face+=txt(brand,100,43,37,color if brand!='Kodak' else '#CF251D','center','condensed' if brand!='Kodak' else 'bold',176)
        face+=txt(title,100,99,31,color,'center','condensed',180)
        face+=txt(number,100,143,34,color,'center','bold',176)
        face+=f'<rect y="164" width="200" height="36" fill="{color}"/>'
        face+=txt(footer,100,186,12,cream,'center','condensed',180)
    # Real paper thickness is deliberately subtle: 3px right edge, 4px lower edge.
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="240" height="280" viewBox="0 0 240 280">
<defs><linearGradient id="edge" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#D2D0C5"/><stop offset="1" stop-color="#827F74"/></linearGradient>
<linearGradient id="paperlight" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#FFFFFF" stop-opacity=".05"/><stop offset=".65" stop-color="#FFFFFF" stop-opacity="0"/><stop offset="1" stop-color="#000000" stop-opacity=".045"/></linearGradient>
<clipPath id="paperface"><rect width="200" height="200" rx="12"/></clipPath></defs>
<g id="{stock}">
<rect x="19" y="43" width="205" height="205" rx="15" fill="#000000" opacity=".08"/>
<rect x="21" y="46" width="204" height="204" rx="16" fill="#000000" opacity=".035"/>
<rect x="20.5" y="40.5" width="200" height="200" rx="12" fill="url(#edge)"/>
<g transform="translate(17 37)"><g clip-path="url(#paperface)">{face}<rect width="200" height="200" fill="url(#paperlight)"/></g>
<rect x=".5" y=".5" width="199" height="199" rx="11.5" fill="none" stroke="#FFFFFF" stroke-opacity=".16" stroke-width=".8"/></g>
</g></svg>'''

def extract_references():
    paths={
      'p1':Path('C:/Users/ADMINI~1/AppData/Local/Temp/codex-clipboard-893047b2-6394-44f7-a7b6-6fe629ff5233.jpg'),
      'p2':Path('C:/Users/ADMINI~1/AppData/Local/Temp/codex-clipboard-1a7df3a6-59a8-4ba5-aa58-eba79078fe45.jpg'),
    }
    destination=OUT/'reference-panels';destination.mkdir(exist_ok=True)
    sources={key:Image.open(path) for key,path in paths.items() if path.exists()}
    for stock,(sheet,row,col) in REFERENCE_PANELS.items():
        if sheet not in sources:continue
        if sheet=='p1':x=[72,721,1370][col];y=[72,720,1368,2016][row];side=600
        else:x=[60,503,984,1428][col];y=[420,864,1308,1753][row];side=420
        # Last-row references stop before collage watermark; final footer is remastered.
        h=(510 if sheet=='p1' else 355) if row==3 else side
        sources[sheet].crop((x,y,x+side,y+h)).save(destination/(stock+'.png'))

def main():
    extract_references();artwork=[]
    for kind,definitions in [('film',FILMS),('paper',PAPERS)]:
        for definition in definitions:
            svg=label(definition);stock=definition[0]
            (OUT/'svg'/f'{stock}.svg').write_text(svg,encoding='utf-8')
            artwork.append({'id':stock,'type':kind,'svg':svg,'label':definition[2]+' '+definition[3]+' '+definition[4],
                'reference':list(REFERENCE_PANELS[stock]) if stock in REFERENCE_PANELS else 'matching label family',
                'design':'Remastered softly rounded paper label; subtle paper edge, no cartridge'})
    board=[]
    for i,item in enumerate(artwork):
        x=40+i%8*260;y=60+i//8*310
        inside=item['svg'].split('>',1)[1].rsplit('</svg>',1)[0]
        inside=inside.replace('id="',f'id="n{i}_').replace('url(#',f'url(#n{i}_')
        board.append(f'<g id="{item["id"]}" transform="translate({x} {y})">{inside}</g>')
        board.append(txt(item['label'],x+120,y+273,10,'#D7D3C7','center',maxwidth=240))
    svg='<svg xmlns="http://www.w3.org/2000/svg" width="2160" height="1340" viewBox="0 0 2160 1340">'+''.join(board)+'</svg>'
    (OUT/'stock-artwork-board.svg').write_text(svg,encoding='utf-8')
    (OUT/'artwork-manifest.json').write_text(json.dumps([{k:v for k,v in item.items() if k!='svg'} for item in artwork],ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Created {len(artwork)} remastered square label artworks; {len(REFERENCE_PANELS)} reference matches')

if __name__=='__main__':main()
