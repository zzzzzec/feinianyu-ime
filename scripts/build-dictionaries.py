"""Build bundled public dictionaries. pip install -r dictionaries-requirements.txt."""
import argparse,bisect,gzip,hashlib,json,math,re,sqlite3,urllib.request,urllib.parse
from pathlib import Path
from opencc import OpenCC
from pypinyin import lazy_pinyin,load_phrases_dict,Style

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'app/src/main/assets/dictionaries'
PUNCT='，。！？；：、,.!?;:\n'
cc=OpenCC('t2s')
def han(ch):return '\u3400'<=ch<='\u9fff' or '\U00020000'<=ch<='\U0003134f'
def norm(s):return ''.join(c for c in cc.convert(s) if han(c))
def fetch(manifest,cache):
 for source in manifest['files']:
  path=cache/source['path'];path.parent.mkdir(parents=True,exist_ok=True)
  if path.is_file():data=path.read_bytes()
  else:
   url='https://raw.githubusercontent.com/'+manifest['repo']+'/'+manifest['revision']+'/'+urllib.parse.quote(source['path'])
   data=urllib.request.urlopen(url,timeout=60).read();path.write_bytes(data)
  assert len(data)==source['size'] and hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()==source['sha'],path
 return cache
def walk(obj):
 if isinstance(obj,list):
  for x in obj:yield from walk(x)
 elif isinstance(obj,dict):
  if isinstance(obj.get('paragraphs') or obj.get('para'),list):yield obj
  elif isinstance(obj.get('content'),list):yield from walk(obj['content'])
def build(poetry,thu,cache):
 OUT.mkdir(parents=True,exist_ok=True)
 db=cache/'fixed.sqlite';db.unlink(missing_ok=True)
 con=sqlite3.connect(db)
 con.executescript('PRAGMA journal_mode=OFF;PRAGMA synchronous=OFF; CREATE TABLE poems(id INTEGER PRIMARY KEY,title TEXT,author TEXT,body TEXT,source TEXT,priority INTEGER); CREATE TABLE prefixes(k TEXT,poem_id INTEGER,start INTEGER,PRIMARY KEY(k,poem_id,start)) WITHOUT ROWID; CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT);')
 seen=set();count=0;entries=0
 popular=set()
 for line in (cache/'thu/data/THUOCL_poem.txt').read_text(encoding='utf-8-sig').splitlines():
  fields=line.split('\t')
  if len(fields)<2 or int(fields[1].strip())<20:continue
  for phrase in re.split('['+re.escape(PUNCT)+']',fields[0].strip()):
   key=norm(phrase)
   if len(key)>=4:popular.add(key)
 def add(title,author,body,source,priority):
  nonlocal count,entries
  positions=[i for i,c in enumerate(body) if han(c)];canonical=''.join(body[i] for i in positions)
  identity=(author,canonical)
  if identity in seen:return
  seen.add(identity);count+=1
  con.execute('INSERT INTO poems VALUES(?,?,?,?,?,?)',(count,title,author,body,source,priority))
  for match in re.finditer('[^'+re.escape(PUNCT)+']+',body):
   clause=match.group()
   if sum(han(c) for c in clause)<4 or re.search(r'[□{}<>\[\]（）()A-Za-z0-9]',clause):continue
   start=match.start();key=canonical[bisect.bisect_left(positions,start):][:24]
   utf16_start=len(body[:start].encode('utf-16-le'))//2
   if len(key)>=4:con.execute('INSERT OR IGNORE INTO prefixes VALUES(?,?,?)',(key,count,utf16_start));entries+=1
 for source in sorted(poetry['files'],key=lambda x:(0 if '三百首' in x['path'] else 1 if 'qianjiashi' in x['path'] else 2,x['path'])):
  path=source['path']
  if not path.endswith('.json'):continue
  priority=100 if '三百首' in path else 80 if 'qianjiashi' in path else 20
  for poem in walk(json.loads((args.poetry_raw/path).read_bytes())):
   parts=poem.get('paragraphs') or poem.get('para')
   if not all(isinstance(p,str) for p in parts):continue
   body=cc.convert('\n'.join(re.sub(r'\s+','',p) for p in parts))
   if priority<80 and not any(''.join(c for c in clause if han(c)) in popular for clause in re.split('['+re.escape(PUNCT)+']',body)):continue
   title=cc.convert(str(poem.get('title') or poem.get('chapter') or poem.get('rhythmic') or '佚题'))
   author=cc.convert(re.sub(r'[（(][^）)]*[）)]','',str(poem.get('author') or '佚名'))).strip()
   add(title,author,body,path,priority)
  con.commit()
  print('Indexed '+path+': '+str(count)+' works',flush=True)
 # Only long, fixed idioms are continuations. IT terms and names never enter this table.
 idiom_count=0
 for line in (cache/'thu/data/THUOCL_chengyu.txt').read_text(encoding='utf-8-sig').splitlines():
  fields=line.split('\t')
  if len(fields)<2:continue
  word,df=(x.strip() for x in fields[:2])
  if 8<=len(norm(word))<=24 and all(han(c) or c in PUNCT for c in word):
   add('成语固定搭配','',word,'THUOCL_chengyu.txt',60);idiom_count+=1
 metadata={'id':'fixed-20261031-v1','schema':'1','poems':str(count),'entries':str(entries),'long_idioms':str(idiom_count),'poetry_revision':poetry['revision'],'thu_revision':thu['revision']}
 con.executemany('INSERT INTO metadata VALUES(?,?)',metadata.items());con.execute('PRAGMA user_version=1');con.commit();con.close()
 # AAPT transparently expands/renames .gz assets; keep these gzip bytes under .bin.
 compressed=OUT/'fixed-v1.sqlite.bin'
 with compressed.open('wb') as target:
  with gzip.GzipFile(filename='',fileobj=target,mode='wb',mtime=0) as zipped:zipped.write(db.read_bytes())
 # Pinyin packs: retain only Han words, one pronunciation per character, modest non-positive costs.
 load_phrases_dict({'荀彧':[['xún'],['yù']],'尉迟恭':[['yù'],['chí'],['gōng']],'长孙无忌':[['zhǎng'],['sūn'],['wú'],['jì']],'单雄信':[['shàn'],['xióng'],['xìn']]})
 packs=[]
 for category,title in [('IT','计算机'),('lishimingren','历史人名'),('diming','地名'),('chengyu','成语')]:
  rows=[];skipped=0;maxdf=1
  values=[]
  for line in (cache/('thu/data/THUOCL_'+category+'.txt')).read_text(encoding='utf-8-sig').splitlines():
   fields=line.split('\t')
   if len(fields)<2:skipped+=1;continue
   word,df=(x.strip() for x in fields[:2])
   if not df.isdigit():skipped+=1;continue
   if not 2<=len(word)<=16 or not all(han(c) for c in word):skipped+=1;continue
   values.append((word,max(int(df),1)));maxdf=max(maxdf,int(df))
  for word,df in values:
   codes=lazy_pinyin(word,style=Style.NORMAL,errors='default')
   if len(codes)!=len(word) or any(not re.fullmatch('[a-z]+',c) for c in codes):skipped+=1;continue
   weight=max(-5.0,min(-0.5,math.log10(df/maxdf)-0.5))
   rows.append(word+' '+"'".join(codes)+' '+format(weight,'.3f'))
  if category=='IT':
   for word in ['大语言模型','生成式人工智能','提示词','上下文窗口','向量数据库','检索增强生成','量化模型','端侧推理','多模态模型','注意力机制','低秩适配','持续集成','容器编排']:
    rows.append(word+' '+"'".join(lazy_pinyin(word))+' -1.500')
  name='feinianyu-'+category+'-v1.txt';data=('\n'.join(sorted(set(rows)))+'\n').encode()
  (OUT/name).write_bytes(data)
  packs.append({'id':category+'-v1','name':'飞念语-'+title,'asset':name,'entries':len(rows),'sha256':hashlib.sha256(data).hexdigest(),'skipped':skipped})
 result={'schema':1,'id':metadata['id'],'database':compressed.name,'sha256':hashlib.sha256(db.read_bytes()).hexdigest(),'bytes':db.stat().st_size,'compressed_bytes':compressed.stat().st_size,'poems':count,'entries':entries,'long_idioms':idiom_count,'pinyin':packs,'sources':{'poetry':poetry['revision'],'THUOCL':thu['revision']}}
 (OUT/'manifest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 (OUT/'LICENSE-chinese-poetry.txt').write_bytes((args.poetry_raw/'LICENSE').read_bytes())
 (OUT/'LICENSE-THUOCL.txt').write_bytes((cache/'thu/LICENSE').read_bytes())
 print(json.dumps(result,ensure_ascii=False,indent=2))

if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--poetry-raw',type=Path,default=ROOT/'.private/dictionary-sources/poetry');args=parser.parse_args()
 cache=ROOT/'.private/dictionary-sources';cache.mkdir(parents=True,exist_ok=True)
 poetry=json.loads((ROOT/'scripts/dictionaries-poetry-sources.json').read_text(encoding='utf-8'))
 thu=json.loads((ROOT/'scripts/dictionaries-thu-sources.json').read_text(encoding='utf-8'))
 fetch(poetry,args.poetry_raw);fetch(thu,cache/'thu');build(poetry,thu,cache)
