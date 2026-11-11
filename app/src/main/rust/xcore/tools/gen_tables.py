import re, sys
J='/home/claude/src_winlite/WinLite/app/src/main/java/com/winlator/cmod/xserver/'
out=[]
# atoms
a=open(J+'Atom.java').read()
m=re.search(r'Arrays\.asList\((.*?)\)\);', a, re.S)
names=[s.strip() for s in re.findall(r'null|"[^"]*"', m.group(1))]
out.append('/// Predefined atoms; index == atom id (index 0 is unused). Generated from Atom.java.')
out.append('pub const PREDEFINED_ATOMS: &[&str] = &[')
for n in names:
    out.append('    "",' if n=='null' else f'    {n},')
out.append('];\n')
# keycodes
k=open(J+'XKeycode.java').read()
codes=dict(re.findall(r'(KEY_[A-Z0-9_]+)\((\d+)\)', k))
out.append('/// X keycodes by name (XKeycode.java).')
for n,v in codes.items(): out.append(f'pub const {n}: u8 = {v};')
out.append('')
kb=open(J+'Keyboard.java').read()
pairs=re.findall(r'setKeysyms\(XKeycode\.(KEY_[A-Z0-9_]+)\.id,\s*(\d+),\s*(\d+)\)', kb)
out.append('/// Default keysyms (keycode, keysym0, keysym1) from Keyboard.createKeyboard.')
out.append('pub const DEFAULT_KEYSYMS: &[(u8, u32, u32)] = &[')
for n,a1,b1 in pairs: out.append(f'    ({n}, {a1}, {b1}),')
out.append('];')
open('/home/claude/src_winlite/WinLite/app/src/main/rust/xcore/src/proto_tables.rs.tmp','w').write('\n'.join(out)+'\n')
print(len(names)-1,'atoms;',len(codes),'keycodes;',len(pairs),'keysym rows')
