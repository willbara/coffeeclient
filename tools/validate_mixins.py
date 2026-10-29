#!/usr/bin/env python3
"""Statically validate Coffee mixins: for each injection annotation, check that the
@At target instruction actually occurs in the selected target method's body."""
import glob, os, re, subprocess, sys, functools

JAVAP = os.environ.get("JAVAP", "javap")
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MIXIN_DIR = os.path.join(REPO, "src/main/java/dev/coffeeclient/mixin")
MC_JAR = os.environ.get("MC_JAR")
LIB_DIRS = [d for d in os.environ.get("LIB_DIRS", "").split(os.pathsep) if d]
if not MC_JAR:
    sys.exit("set MC_JAR to the Minecraft 26.2 client jar (optionally LIB_DIRS: jar folders separated by "
             + repr(os.pathsep) + "; JAVAP: the javap binary). See tools/README.md")

def build_cp():
    jars = [MC_JAR]
    for d in LIB_DIRS:
        for root, _, files in os.walk(d):
            for f in files:
                if f.endswith(".jar") and not f.endswith("-sources.jar"):
                    jars.append(os.path.join(root, f))
    # dedupe preserving order
    seen, out = set(), []
    for j in jars:
        if j not in seen:
            seen.add(j); out.append(j)
    return os.pathsep.join(out)

CP = build_cp()

@functools.lru_cache(maxsize=None)
def javap(cls):
    try:
        r = subprocess.run([JAVAP, "-p", "-s", "-c", "-cp", CP, cls],
                           capture_output=True, text=True, timeout=120)
    except Exception as e:
        return ("ERR", str(e))
    if r.returncode != 0:
        return ("ERR", r.stderr.strip() or "javap failed")
    return ("OK", r.stdout)

def parse_class(out, cls):
    """Return (fields:set(name:desc), methods:list of (name,desc,body_text))."""
    fields, methods = set(), []
    lines = out.splitlines()
    i = 0
    cur = None
    while i < len(lines):
        line = lines[i]
        m = re.match(r"^    descriptor: (\S+)$", line)
        if m and i > 0:
            desc = m.group(1)
            header = lines[i-1].strip()
            # header like "public static boolean foo(java.lang.String);" or "private int field;" or "static {};"
            if header.endswith(");") or header.endswith(";"):
                # name = text before '(' if present else token before ';'
                if "(" in header:
                    name = header.split("(")[0].split()[-1]
                    if name == "static":  # static {} -> clinit
                        name = "<clinit>"
                    # constructor: name == simple class name
                    simple = header.split("(")[0].split()[-1]
                    if simple in (cls, cls.rsplit(".",1)[-1]):
                        name = "<init>"
                    # body
                    body_lines = []
                    j = i + 1
                    if j < len(lines) and lines[j].strip().startswith("Code:"):
                        j += 1
                        while j < len(lines):
                            l2 = lines[j]
                            if re.match(r"^    descriptor: \S+$", l2):
                                break
                            nxt_header = lines[j].strip()
                            if nxt_header.endswith(");") and j+1 < len(lines) and re.match(r"^    descriptor: \S+$", lines[j+1]):
                                break
                            body_lines.append(l2)
                            j += 1
                    cur = (name, desc, "\n".join(body_lines))
                    methods.append(cur)
                else:
                    name = header.rstrip(";").split()[-1]
                    fields.add(f"{name}:{desc}")
            # skip special names like "java.lang.Object.wait" headers - handled above
        i += 1
    return fields, methods

def balanced(text, start, op="(", cl=")"):
    """text[start] must be the opening char; return content inside, index after close."""
    assert text[start] == op, repr(text[start:start+20])
    depth = 0
    i = start
    in_str = False
    esc = False
    while i < len(text):
        c = text[i]
        if in_str:
            if esc: esc = False
            elif c == "\\": esc = True
            elif c == '"': in_str = False
        else:
            if c == '"': in_str = True
            elif c == op: depth += 1
            elif c == cl:
                depth -= 1
                if depth == 0:
                    return text[start+1:i], i+1
        i += 1
    return text[start+1:], len(text)

def find_annotations(src):
    """Yield (annname, content) for each @Annotation(...) at top level."""
    for m in re.finditer(r"@(Inject|ModifyArg|ModifyArgs|ModifyVariable|Redirect|ModifyConstant|ModifyExpressionValue|WrapOperation|WrapWithCondition|Group)\s*\(", src):
        name = m.group(1)
        p = m.end() - 1
        content, _ = balanced(src, p)
        yield name, content

def extract_kv(content, key):
    """Return list of balanced sub-expressions for `key = ...` or `key=...`."""
    out = []
    for m in re.finditer(r"\b" + key + r"\s*=\s*", content):
        i = m.end()
        if i < len(content) and content[i] == "{":
            inner, _ = balanced(content, i, "{", "}")
            out.append(inner)
        elif i < len(content) and content[i] == "(":
            inner, _ = balanced(content, i)
            out.append(inner)
        else:
            # up to comma at depth 0
            depth = 0; j = i
            while j < len(content):
                c = content[j]
                if c in "([{": depth += 1
                elif c in ")]}" :
                    if depth == 0: break
                    depth -= 1
                elif c == "," and depth == 0: break
                j += 1
            out.append(content[i:j])
    return out

MNEM = {
    "GETFIELD": "getfield", "PUTFIELD": "putfield",
    "GETSTATIC": "getstatic", "PUTSTATIC": "putstatic",
    "INVOKEVIRTUAL": "invokevirtual", "INVOKESTATIC": "invokestatic",
    "INVOKESPECIAL": "invokespecial", "INVOKEINTERFACE": "invokeinterface",
}
OPCODE_NAMES = {
    "GETFIELD": "getfield", "PUTFIELD": "putfield", "GETSTATIC": "getstatic", "PUTSTATIC": "putstatic",
    "INVOKEVIRTUAL": "invokevirtual", "INVOKESTATIC": "invokestatic", "INVOKESPECIAL": "invokespecial",
    "INVOKEINTERFACE": "invokeinterface", "INVOKEDYNAMIC": "invokedynamic",
}

def strings(s):
    return re.findall(r'"([^"]*)"', s)

def resolve_class(simple, imports):
    if "." in simple and simple.split(".")[0] in imports:
        head, rest = simple.split(".", 1)
        return imports[head] + "$" + rest.replace(".", "$")
    if simple in imports: return imports[simple]
    return simple

CHECKS=[0]
def main():
    problems = []
    for path in sorted(glob.glob(os.path.join(MIXIN_DIR, "*.java"))):
        src = open(path).read()
        imports = {}
        for im in re.findall(r"import\s+(?:static\s+)?([\w.]+);", src):
            imports[im.rsplit(".",1)[-1]] = im
        mix = re.search(r"@Mixin\s*\(\s*(?:value\s*=\s*)?\{?\s*([\w.$]+)\.class", src)
        if not mix:
            continue
        target = resolve_class(mix.group(1), imports)
        status, out = javap(target)
        if status != "OK":
            problems.append((path, f"CANNOT JAVAP target {target}: {out}"))
            continue
        fields, methods = parse_class(out, target)
        by_name = {}
        for (n, d, b) in methods:
            by_name.setdefault(n, []).append((d, b))

        for annname, content in find_annotations(src):
            method_sels = strings(" ".join(extract_kv(content, "method")))
            if not method_sels:
                continue
            at_exprs = extract_kv(content, "at")
            ats = []
            for a in at_exprs:
                # a may be "{ @At(...), @At(...) }"
                for mm in re.finditer(r"@At\s*\(", a):
                    inner, _ = balanced(a, mm.end()-1)
                    ats.append(inner)
            if not ats:
                # @At passed as value of annotation? unusual; skip
                continue
            # resolve method selectors -> list of (sel, [(desc, body)])
            sel_methods = []
            for sel in method_sels:
                sel = sel.strip().rstrip("*")
                if "(" in sel:
                    nm = sel.split("(")[0]
                    desc = sel[sel.index("("):]
                    cand = [mb for (n,d,mb) in methods if n == nm and d == desc]
                    if not cand:
                        problems.append((path, f"[{annname}] method selector NOT FOUND: {sel} in {target}"))
                        continue
                    sel_methods.append((sel, cand))
                else:
                    if sel not in by_name:
                        problems.append((path, f"[{annname}] method selector NOT FOUND (name only): {sel} in {target}"))
                        continue
                    sel_methods.append((sel, [b for (d,b) in by_name[sel]]))
            for at in ats:
                am = re.search(r'(?:value\s*=\s*)?"?([A-Z_]+)"?', at)
                kind = am.group(1) if am else "?"
                if kind not in ("INVOKE","INVOKEVIRTUAL","INVOKESTATIC","INVOKESPECIAL","INVOKEINTERFACE","INVOKE_ASSIGN",
                                "FIELD","GETFIELD","PUTFIELD","GETSTATIC","PUTSTATIC","NEW","NEWINVOKE","CONSTANT","JUMP"):
                    continue
                tg = extract_kv(at, "target")
                if not tg:
                    continue
                target_str = tg[0].strip().strip('"')
                tm = re.match(r"L([^;]+);(.+)", target_str)
                if not tm:
                    continue
                towner, rest = tm.group(1), tm.group(2)
                if "(" in rest:
                    tname, tdesc = rest[:rest.index("(")], rest[rest.index("("):]
                elif ":" in rest:
                    tname, tdesc = rest.split(":", 1)
                else:
                    tname, tdesc = rest, None
                if tdesc is None:
                    continue
                req_mnem = None
                if kind in MNEM:
                    req_mnem = MNEM[kind]
                opm = extract_kv(at, "opcode")
                if opm:
                    tok = opm[0].strip().split(".")[-1]
                    req_mnem = OPCODE_NAMES.get(tok, tok.lower())
                ordm = extract_kv(at, "ordinal")
                ordinal = int(ordm[0].strip()) if ordm else -1
                # candidate methods
                found = False
                matches = 0
                for sel, cands in sel_methods:
                    for body in cands:
                        c = 0
                        for bl in body.splitlines():
                            if req_mnem:
                                mm2 = re.match(r"^\s*\d+:\s+(\w+)\s", bl)
                                if not mm2 or mm2.group(1) != req_mnem:
                                    continue
                            bm = re.search(r"//\s*(?:Method|InterfaceMethod|Field)\s+(\S+)", bl)
                            if not bm: continue
                            tok = bm.group(1)
                            if ":" not in tok: continue
                            left, desc = tok.rsplit(":", 1)
                            if "." in left:
                                own, nm = left.rsplit(".", 1)
                            else:
                                own, nm = None, left
                            if nm == tname and desc == tdesc:
                                if own is None or own == towner or own.endswith("/"+towner.split("/")[-1]):
                                    c += 1
                        matches += c
                if ordinal >= 0:
                    found = matches > ordinal
                else:
                    found = matches > 0
                CHECKS[0]+=1
                if not found:
                    problems.append((path, f"[{annname}] @At {kind} target NOT in selected method body (matches={matches}, ordinal={ordinal}, opcode={req_mnem}): {target_str}  ** method={method_sels}"))
    print(f"Validated {len(glob.glob(os.path.join(MIXIN_DIR,'*.java')))} mixin files; {CHECKS[0]} @At target checks performed")
    if not problems:
        print("OK: no instruction-placement problems found")
    for p, msg in problems:
        print(f"\n{os.path.basename(p)}:\n   {msg}")

main()
