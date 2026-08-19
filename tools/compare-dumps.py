#!/usr/bin/env python3
"""Compare accessibility dumps to find what uniquely identifies each screen.

A fingerprint is only useful if it appears on the screen you want to block and
on none of the screens you want to leave alone. Eyeballing the view-id lists
misses overlaps -- YouTube's watch page carries `reel_time_bar` even though it
is not Shorts -- so the comparison is done set-wise here.

    ./tools/compare-dumps.py dumps/**/*youtube-*.json
"""
import json, sys, os, collections

def load(path):
    d = json.load(open(path))
    label = os.path.basename(path).rsplit('-', 1)[-1].removesuffix('.json')
    ids = {n['viewId'].split('id/')[-1] for n in d['nodes'] if n.get('viewId')}
    scrollable = {
        (n['viewId'].split('id/')[-1] if n.get('viewId') else n.get('className', '?'))
        for n in d['nodes'] if n.get('scrollable')
    }
    return label, ids, scrollable, d

def main(paths):
    screens = {}
    for p in paths:
        label, ids, scroll, d = load(p)
        screens[label] = (ids, scroll, d)

    print("=== screens ===")
    for label, (ids, scroll, d) in screens.items():
        print(f"  {label:8} {d['nodeCount']:4} nodes, {len(ids):3} view-ids, scrollable: {sorted(scroll) or '-'}")

    print("\n=== view-ids UNIQUE to each screen (candidate fingerprints) ===")
    for label, (ids, _, _) in screens.items():
        others = set().union(*[o for l, (o, _, _) in screens.items() if l != label]) if len(screens) > 1 else set()
        unique = sorted(ids - others)
        print(f"\n  {label}:")
        for u in unique:
            print(f"      {u}")
        if not unique:
            print("      (none -- this screen has no distinguishing view-id)")

    print("\n=== shared by ALL screens (useless as fingerprints) ===")
    common = set.intersection(*[ids for ids, _, _ in screens.values()])
    print("   ", ", ".join(sorted(common)) or "(none)")

if __name__ == "__main__":
    main(sys.argv[1:])
