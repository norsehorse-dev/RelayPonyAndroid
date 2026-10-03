#!/usr/bin/env python3
"""
Malicious and edge-case zip archives for the folder extractor (PROTOCOL_v3 section 12).

Archives are built byte by byte with a tiny writer below rather than Python's zipfile, so every
field (flags, methods, external attributes, zip64 records, offsets, CRCs) can be set to exactly
the wrong value a case needs. The expected outcome of each case is written next to it, and the
Kotlin (SafeZipTests) and Swift (SafeZipTests) suites assert the same outcomes.

Writes zip_corpus.json next to this script.
"""
import base64
import json
import os
import struct
import zlib

DOS_TIME = 0
DOS_DATE = (0 << 9) | (1 << 5) | 1  # 1980-01-01
UNIX_FILE = (0o100644 << 16)
UNIX_DIR = (0o040755 << 16) | 0x10
UNIX_LINK = (0o120777 << 16)
MADE_BY_UNIX = (3 << 8) | 20
MADE_BY_DOS = 20


def deflate(data, level=6):
    c = zlib.compressobj(level, zlib.DEFLATED, -15)
    return c.compress(data) + c.flush()


def entry(name, data=b"", method=8, **kw):
    e = dict(name=name, data=data, method=method)
    e.update(kw)
    return e


def build(entries, zip64=False, eocd_disk=0, alias=None, trailing=b"", cd_offset_bump=0,
          bad_local_sig=None):
    """alias maps a central index to another entry's index: its central record points at that
    entry's local header (overlapping data)."""
    out = bytearray()
    central = []
    offsets = []
    for i, e in enumerate(entries):
        name = e["name"].encode("utf-8") if isinstance(e["name"], str) else e["name"]
        data = e["data"]
        method = e["method"]
        flags = e.get("flags", 0)
        if isinstance(e["name"], str) and any(ord(ch) > 127 for ch in e["name"]):
            flags |= 0x800
        if "comp" in e:
            comp = e["comp"]
        elif method == 8:
            comp = deflate(data)
        else:
            comp = data
        crc = e.get("crc", zlib.crc32(data) & 0xFFFFFFFF)
        usize = e.get("usize", len(data))
        csize = len(comp)
        made_by = e.get("made_by", MADE_BY_UNIX)
        ext = e.get("ext", UNIX_DIR if name.endswith(b"/") else UNIX_FILE)
        offsets.append(len(out))
        sig = 0x04034B50 if bad_local_sig != i else 0x04034B51
        if zip64:
            lextra = struct.pack("<HHQQ", 1, 16, usize, csize)
            out += struct.pack("<IHHHHHIIIHH", sig, 45, flags, method, DOS_TIME, DOS_DATE, crc,
                               0xFFFFFFFF, 0xFFFFFFFF, len(name), len(lextra))
            out += name + lextra
        else:
            out += struct.pack("<IHHHHHIIIHH", sig, 20, flags, method, DOS_TIME, DOS_DATE, crc,
                               csize, usize, len(name), 0)
            out += name
        out += comp
        central.append((name, flags, method, crc, csize, usize, made_by, ext))
    cd_start = len(out)
    for i, (name, flags, method, crc, csize, usize, made_by, ext) in enumerate(central):
        off = offsets[alias[i]] if alias and i in alias else offsets[i]
        if zip64:
            cextra = struct.pack("<HHQQQ", 1, 24, usize, csize, off)
            out += struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, made_by, 45, flags, method,
                               DOS_TIME, DOS_DATE, crc, 0xFFFFFFFF, 0xFFFFFFFF, len(name),
                               len(cextra), 0, 0, 0, ext, 0xFFFFFFFF)
            out += name + cextra
        else:
            out += struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, made_by, 20, flags, method,
                               DOS_TIME, DOS_DATE, crc, csize, usize, len(name), 0, 0, 0, 0,
                               ext, off)
            out += name
    cd_size = len(out) - cd_start
    n = len(central)
    if zip64:
        eocd64 = len(out)
        out += struct.pack("<IQHHIIQQQQ", 0x06064B50, 44, MADE_BY_UNIX, 45, 0, 0, n, n, cd_size,
                           cd_start + cd_offset_bump)
        out += struct.pack("<IIQI", 0x07064B50, 0, eocd64, 1)
        out += struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, 0xFFFF, 0xFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0)
    else:
        out += struct.pack("<IHHHHIIH", 0x06054B50, eocd_disk, eocd_disk, n, n, cd_size,
                           cd_start + cd_offset_bump, 0)
    out += trailing
    return bytes(out)


CASES = []


def ok(cid, archive, zipbytes, root, files, dirs=(), skipped=0, limits=None, note=""):
    CASES.append(dict(id=cid, note=note, archiveName=archive,
                      zip=base64.b64encode(zipbytes).decode(), limits=limits or {},
                      expect=dict(root=root, files=[dict(path=p, content=c) for p, c in files],
                                  dirs=list(dirs), skipped=skipped)))


def bad(cid, archive, zipbytes, error, phase="plan", limits=None, note=""):
    CASES.append(dict(id=cid, note=note, archiveName=archive,
                      zip=base64.b64encode(zipbytes).decode(), limits=limits or {},
                      expect=dict(error=error, phase=phase)))


T = lambda s: s.encode("utf-8")

# ---- archives that extract ----

ok("simple", "Photos.zip", build([
    entry("Photos/", method=0),
    entry("Photos/a.txt", T("alpha")),
    entry("Photos/trip/b.txt", T("bravo")),
]), "Photos", [("a.txt", "alpha"), ("trip/b.txt", "bravo")],
    note="single top-level folder becomes the root")

ok("stored", "S.zip", build([
    entry("S/x.txt", T("stored bytes"), method=0),
]), "S", [("x.txt", "stored bytes")])

ok("no_common_root", "Bundle.zip", build([
    entry("a.txt", T("one")),
    entry("b/c.txt", T("two")),
]), "Bundle", [("a.txt", "one"), ("b/c.txt", "two")],
    note="no shared top folder: wrapped in a folder named after the archive")

ok("root_file_only", "Notes.ZIP", build([
    entry("Notes", T("a file named like the archive")),
]), "Notes", [("Notes", "a file named like the archive")],
    note="a single file at the top is not a root folder")

ok("empty_dirs", "E.zip", build([
    entry("E/", method=0),
    entry("E/keep/", method=0),
    entry("E/keep/", method=0),
    entry("E/x/y/", method=0),
    entry("E/f.txt", T("f")),
]), "E", [("f.txt", "f")], dirs=["keep", "x/y"],
    note="explicit directory entries are reported once, root itself dropped")

ok("macos_junk", "M.zip", build([
    entry("M/a.txt", T("real")),
    entry("__MACOSX/M/._a.txt", T("appledouble")),
    entry("M/.DS_Store", T("finder")),
    entry("M/.hidden", T("dotfile kept")),
]), "M", [("a.txt", "real"), (".hidden", "dotfile kept")], skipped=2)

ok("symlink_skipped", "L.zip", build([
    entry("L/target.txt", T("t")),
    entry("L/link", T("target.txt"), method=0, ext=UNIX_LINK),
]), "L", [("target.txt", "t")], skipped=1, note="symlinks are never created")

ok("symlink_dos_attr_is_file", "D.zip", build([
    entry("D/link", T("not a link"), method=0, made_by=MADE_BY_DOS, ext=UNIX_LINK),
]), "D", [("link", "not a link")], note="link mode only counts when made-by is Unix")

ok("backslashes", "W.zip", build([
    entry("W\\sub\\c.txt", T("win")),
]), "W", [("sub/c.txt", "win")])

ok("dot_and_empty_components", "P.zip", build([
    entry("P/./x/./y.txt", T("y")),
    entry("P//z.txt", T("z")),
]), "P", [("x/y.txt", "y"), ("z.txt", "z")])

ok("unicode_nfc", "U.zip", build([
    entry("U/Cafe\u0301.txt", T("nfd in, nfc out")),
]), "U", [("Caf\u00e9.txt", "nfd in, nfc out")])

ok("latin1_names", "Old.zip", build([
    entry(b"Old/caf\xe9.txt", T("latin-1 name, no utf-8 flag")),
]), "Old", [("caf\u00e9.txt", "latin-1 name, no utf-8 flag")])

ok("reserved_chars", "R.zip", build([
    entry('R/what?.txt', T("q")),
    entry('R/a<b>c:d|e*f"g.txt', T("r")),
    entry("R/trailing. . ", T("t")),
    entry("R/  lead.txt", T("l")),
    entry("R/.../x.txt", T("x")),
]), "R", [("what_.txt", "q"), ("a_b_c_d_e_f_g.txt", "r"), ("trailing", "t"),
          ("lead.txt", "l"), ("_/x.txt", "x")])

ok("case_duplicates", "C.zip", build([
    entry("C/a.txt", T("first")),
    entry("C/A.txt", T("second")),
    entry("C/a (2).txt", T("third")),
    entry("C/a.txt", T("fourth")),
]), "C", [("a.txt", "first"), ("A (2).txt", "second"), ("a (2) (2).txt", "third"),
          ("a (3).txt", "fourth")])

ok("dir_case_merge", "Mix.zip", build([
    entry("Docs/a.txt", T("1")),
    entry("docs/b.txt", T("2")),
]), "Mix", [("Docs/a.txt", "1"), ("docs/b.txt", "2")],
    note="differently cased folders merge without conflict")

ok("zip64", "Z.zip", build([
    entry("Z/big-ish.txt", T("zip64 records")),
    entry("Z/s.txt", T("stored"), method=0),
], zip64=True), "Z", [("big-ish.txt", "zip64 records"), ("s.txt", "stored")])

ok("long_name", "N.zip", build([
    entry("N/" + "a" * 300 + ".txt", T("long")),
]), "N", [("a" * 196 + ".txt", "long")], note="components capped at 200 UTF-8 bytes")

ok("empty_archive", "Nothing.zip", build([]), "Nothing", [])

ok("deep_ok", "Deep.zip", build([
    entry("/".join(["d"] * 31) + "/f.txt", T("depth 32")),
]), "d", [("/".join(["d"] * 30) + "/f.txt", "depth 32")])

ok("entries_at_limit", "Lim.zip", build([
    entry("Lim/1.txt", T("1")), entry("Lim/2.txt", T("2")), entry("Lim/3.txt", T("3")),
]), "Lim", [("1.txt", "1"), ("2.txt", "2"), ("3.txt", "3")], limits=dict(maxEntries=3))

# ---- archives that are refused ----

bad("traversal", "t.zip", build([entry("../evil.txt", T("x"))]), "UNSAFE_PATH")
bad("traversal_nested", "t.zip", build([
    entry("F/ok.txt", T("ok")),
    entry("F/sub/../../../evil.txt", T("x")),
]), "UNSAFE_PATH", note="one bad entry refuses the whole archive")
bad("traversal_backslash", "t.zip", build([entry("..\\..\\evil.txt", T("x"))]), "UNSAFE_PATH")
bad("traversal_in_junk", "t.zip", build([entry("__MACOSX/../evil", T("x"))]), "UNSAFE_PATH")
bad("absolute", "t.zip", build([entry("/etc/passwd", T("x"))]), "UNSAFE_PATH")
bad("absolute_backslash", "t.zip", build([entry("\\evil.txt", T("x"))]), "UNSAFE_PATH")
bad("drive_letter", "t.zip", build([entry("C:/Windows/evil.txt", T("x"))]), "UNSAFE_PATH")
bad("drive_relative", "t.zip", build([entry("c:evil.txt", T("x"))]), "UNSAFE_PATH")
bad("fullwidth_dots", "t.zip", build([entry("\uff0e\uff0e/evil.txt", T("x"))]), "UNSAFE_PATH")
bad("fullwidth_slash", "t.zip", build([entry("F/a\uff0f..\uff0fevil.txt", T("x"))]), "UNSAFE_PATH")
bad("nul_in_name", "t.zip", build([entry(b"F/a\x00.txt", T("x"))]), "UNSAFE_PATH")
bad("newline_in_name", "t.zip", build([entry("F/a\n.txt", T("x"))]), "UNSAFE_PATH")
bad("too_deep", "t.zip", build([entry("/".join(["d"] * 32) + "/f.txt", T("x"))]), "TOO_DEEP")
bad("encrypted", "t.zip", build([entry("F/a.txt", T("x"), flags=1)]), "ENCRYPTED")
bad("bzip2", "t.zip", build([entry("F/a.txt", T("x"), method=12, comp=b"BZh91AY&SY")]),
    "UNSUPPORTED_METHOD")
bad("too_many_entries", "t.zip", build([
    entry("F/1", T("1")), entry("F/2", T("2")), entry("F/3", T("3")), entry("F/4", T("4")),
]), "TOO_MANY_ENTRIES", limits=dict(maxEntries=3))
bad("file_too_large", "t.zip", build([entry("F/a", b"x" * 20)]), "TOO_LARGE",
    limits=dict(maxFileBytes=10))
bad("transfer_too_large", "t.zip", build([entry("F/a", b"x" * 20), entry("F/b", b"y" * 20)]),
    "TOO_LARGE", limits=dict(maxTransferBytes=30))
bad("bomb_declared", "t.zip", build([entry("F/zeros", bytes(10 << 20), comp=deflate(bytes(10 << 20), 9))]),
    "TOO_LARGE", limits=dict(maxFileBytes=1 << 20), note="10 MiB of zeros, honestly declared")
bad("no_space", "t.zip", build([entry("F/a", b"x" * 30), entry("F/b", b"y" * 30)]), "NO_SPACE",
    limits=dict(freeBytes=100, freeSpaceMarginBytes=50))
bad("conflict_file_then_dir", "t.zip", build([
    entry("F/a", T("file")), entry("F/a/b.txt", T("inside")),
]), "CONFLICT")
bad("conflict_dir_then_file", "t.zip", build([
    entry("F/a/b.txt", T("inside")), entry("F/A", T("file")),
]), "CONFLICT")
bad("conflict_dir_entry", "t.zip", build([
    entry("F/a", T("file")), entry("F/a/", method=0),
]), "CONFLICT")
bad("overlap", "t.zip", build([
    entry("F/a.txt", T("shared bytes")), entry("F/b.txt", T("other")),
], alias={1: 0}), "OVERLAP", note="two names pointing at one local header")
bad("multi_disk", "t.zip", build([entry("F/a", T("x"))], eocd_disk=1), "MULTI_DISK")
bad("not_a_zip", "t.zip", os.urandom(0) + b"this is not a zip archive at all, just text" * 3,
    "NOT_A_ZIP")
bad("too_short", "t.zip", b"PK\x05\x06", "NOT_A_ZIP")
bad("trailing_garbage", "t.zip", build([entry("F/a", T("x"))], trailing=b"junk"), "NOT_A_ZIP")
bad("cd_out_of_bounds", "t.zip", build([entry("F/a", T("x"))], cd_offset_bump=1000), "CORRUPT")
bad("bad_local_header", "t.zip", build([entry("F/a", T("x"))], bad_local_sig=0), "CORRUPT")
bad("stored_size_mismatch", "t.zip", build([entry("F/a", T("abc"), method=0, usize=5)]), "CORRUPT")
bad("crc_mismatch", "t.zip", build([entry("F/a", T("hello"), crc=0x12345678)]), "CORRUPT",
    phase="extract")
bad("size_lie_bomb", "t.zip", build([entry("F/zeros", bytes(10 << 20), usize=1000)]), "CORRUPT",
    phase="extract", note="declares 1000 bytes, inflates to 10 MiB; must stop at the declared size")
bad("size_short", "t.zip", build([entry("F/a", T("hello"), usize=50)]), "CORRUPT",
    phase="extract")
PATTERN = bytes((i * 7 + i // 3) & 0xFF for i in range(4096))
bad("truncated_deflate", "t.zip", build([entry("F/a", PATTERN, comp=deflate(PATTERN)[:-1])]),
    "CORRUPT", phase="extract", note="the stream is one byte short")
bad("truncated_deflate_half", "t.zip", build([entry("F/a", PATTERN,
                                                    comp=deflate(PATTERN)[:len(deflate(PATTERN)) // 2])]),
    "CORRUPT", phase="extract")
bad("zip64_bad_locator", "t.zip", (lambda b: b[:-42] + b"\x00\x00\x00\x00" + b[-38:])(
    build([entry("F/a", T("x"))], zip64=True)), "CORRUPT")


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    doc = {
        "about": "Folder extractor corpus, PROTOCOL_v3 section 12. Generated by gen_zip_corpus.py.",
        "defaults": {"maxEntries": 10000, "maxFileBytes": 16 << 30, "maxTransferBytes": 64 << 30,
                     "freeSpaceMarginBytes": 500 << 20, "freeBytes": None},
        "cases": CASES,
    }
    with open(os.path.join(here, "zip_corpus.json"), "w") as f:
        json.dump(doc, f, indent=1, ensure_ascii=True)
        f.write("\n")
    print(f"{len(CASES)} cases")
