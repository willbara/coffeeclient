import csv, json, re, collections
import os, sys
if len(sys.argv) < 2:
    sys.exit('usage: build_chatfilter_lists.py <dir with words.json, profanity_en.csv, google-word-list.txt, en.txt> [out dir]')
D=os.path.join(sys.argv[1], '')
OUT=os.path.join(sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src/main/resources/assets/coffeeclient/chatfilter'), '')
def norm(s): return re.sub(r'\s+',' ',s.strip().lower())
def ok(s): return len(s)>=3 and s.isascii() or s=='🖕'
words=[norm(w) for w in json.load(open(D+'words.json'))]
google=[norm(l) for l in open(D+'google-word-list.txt',encoding='utf8') if l.strip()]
en=[norm(l) for l in open(D+'en.txt',encoding='utf8') if l.strip()]
rows=list(csv.DictReader(open(D+'profanity_en.csv',encoding='utf8')))

STOP=set('''hell bum butt fool dumb bloody balls fart farted farting farty poo poop sex sexy sexual sexually sexuality nude nudity pawn escort jerk lust lusting
suck sucks sucker spac ho hoe hoes tit topless viagra cialis panties panty undressing swinger tied up girl on girl on top xx xxx nob knob wang willy willies kinky
kinkster domination playboy booty call bareback bondage strip club pis pises cox fanny gringo pollock dike dink dinks sheet sack finger peter nip crow crows tramp swine
scut tart booty crotch bates bater peen forked nuggets knickers spook spooks hustler greaser dolt dipstick cooties scummy scum finger snatch hooters ramrod diddle
tush tushy beaver lipstick abuse naked bong ganja heroin cocaine lsd pcp xtc valium peyote herpes herpy sperm semen erection erotic hymen genital genitals
bug bugger buggery chav bimbo bimbos blacks foobar mideast illegals lingerie hentai fetish hijacking junkie junky cocky anus rectum rectal clit vulva vag vagina
labia penis nipple nipples breasts breast boob boobs boobie crap crappy reefer stoned stoner pimp hooker hookers prostitute fondle seduce torture tortur lynch
jihad hamas osama usama azazel lucifer jebus herp reich aryan hitler hitlerism hitlerist swastika nazi nazis nazism rape raped raper raping rapist vibrator
camel toe cameltoe skeet shag shagging blowjob sexcam livesex cybersex phone sex phonesex'''.split()) 
# multiword stop entries from the split above are handled individually; keep explicit phrases too
STOP|={'girl on','girl on top','strip club','camel toe','phone sex','booty call','tied up','girl on'}

# Regenerates the ChatFilter built-in lists (casual.txt = slurs/hate/abuse, profanity.txt = extra swearing for Family Friendly)
# from four public word lists. The output is committed; rerun only to rebuild the lists.

# ---------- slur tier (Casual + Family) ----------
SLUR_CANON=('faggot','dyke','tranny','shemale','ladyboy','lesbian','homosexual','fudge packer','carpet muncher','pillow biter','pole smoker')
slur=set()
for r in rows:
    t=norm(r['text']); sev=float(r['severity_rating'])
    cats={r['category_1'],r['category_2'],r['category_3']}
    canon={r['canonical_form_1'],r['canonical_form_2'],r['canonical_form_3']}
    if sev<1.8 or t in STOP: continue
    is_slur = 'racial / ethnic slurs' in cats or (cats&{'sexual orientation / gender'} and canon&set(SLUR_CANON)) \
        or (canon&{'retard','mongoloid'}) or (canon&{'pedophile'}) or ('gay' in canon and sev>=2.2)
    if is_slur and t not in ('abbie',): slur.add(t)
# serious / hate / abuse terms
slur|={'rape','raped','raper','raping','rapist','rapists','molest','molester','incest','zoophilia','beastiality','bestiality','pedo','pedos','pedophile','paedophile',
 'kys','kill yourself','kill your self','hang yourself','neck yourself','swastika','kkk','white power','neonazi','neo nazi','heil hitler','sieg heil','gas the jews',
 'nigger','niggers','nigga','niggas','niggah','niggaz','nigguh','retard','retards','retarded','tard','fag','fags','faggot','faggots','dyke','dykes','tranny','trannies',
 'shemale','chink','chinks','gook','gooks','spic','spics','kike','kikes','coon','coons','wetback','wetbacks','beaner','beaners','raghead','ragheads','towelhead',
 'towelheads','sand nigger','paki','pakis','jap','japs','wop','wops','darkie','darkies','jigaboo','jiggaboo','porch monkey','zipperhead','negro','negroes','honky','honkey','redskin','squaw','mongoloid'}
# word-list slurs from the big json (curated by hand from scanning it)
slur|={'abeed','africoon','abbo','arabush','beaney','bluegum','boong','boonga','buddhahead','burrhead','chinaman','chingchong','ching chong','coolie','cushi','dago','gypo',
 'gyppo','hajji','haji','hebe','heeb','injun','jiggerboo','kaffir','kafir','kaffer','kanake','lugan','mick','muzzie','niglet','nignog','nig nog','piccaninny','pickaninny',
 'polack','ruski','sambo','schwartze','slanteye','slope','spearchucker','tarbaby','tar baby','wigger','whigger','yid','yids','zipperhead','sand coon','dune coon','camel jockey',
 'beastial','bestial','tr00n','troon','troons','tr00ns'}
slur={s for s in slur if ok(s) and s not in {'mick','jigger','jiggs','slope','coolie','sheister','tarbaby'}}

# ---------- general profanity (Family Friendly only) ----------
cnt=collections.Counter()
srcs=[set(words),set(google),set(en),{norm(r['text']) for r in rows}]
for s in srcs:
    for w in s: cnt[w]+=1
prof=set()
for w,c in cnt.items():
    if c>=2 and w not in STOP and ok(w): prof.add(w)
for r in rows:
    t=norm(r['text'])
    if float(r['severity_rating'])>=1.2 and t not in STOP and ok(t): prof.add(t)
prof|={'damn','damned','goddamn','goddamned','goddammit','dammit','damnit','crap','crappy','piss','pissed','pissing','pisser','bastard','bitch','shit','shitty','fuck','fucking','fucked','fucker','asshole','dick','cock','cunt','pussy','twat','wank','wanker','whore','slut','bollocks','bullshit','wtf','stfu','gtfo','porn','porno','anal','dildo','cum','jizz','boner','blowjob','orgy','bdsm','milf','hentai','fetish'}
prof-= {'ho','hoe','hoes'}
prof-=slur
prof={p for p in prof if p not in STOP or p in {'crap','crappy','anal','dildo','blowjob','porn','porno','hentai','fetish','sperm','semen'}}
prof={p for p in prof if ok(p)}
open(OUT+'casual.txt','w',encoding='utf8').write('\n'.join(sorted(slur))+'\n')
open(OUT+'profanity.txt','w',encoding='utf8').write('\n'.join(sorted(prof))+'\n')
print(len(slur),len(prof))
