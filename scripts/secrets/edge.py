"""The World's Edge Citadel: one fortress at the world border (EdgeKeep.java places it there by itself), the only home
of Mjolnir. Harder than the Tempest Keep in every room.

Seven sections in a row along +x: an entrance hall that seals behind you, a lever-code room (four of eight colours,
and a wrong guess calls lightning, vexes and wither skeletons), a parkour run over lava (fall and you burn; a breeze and
a blaze try to knock you in), a dark maze with a lever at its heart, the Trial Hall (three waves of elite mobs, the gate
opens when the last one falls), the Edge Sovereign's arena (locks while it lives) and the vault. The mod works the
gates, the puzzles, the waves and the boss from layout.json, which this writes next to the template.
"""
import math
import random

import build as b
from build import Template

F = 2                      # floor height (0..1 is foundation)
X, Y, Z = 206, 34, 41
MID = 20                   # the citadel's centre line (z)
WALL, FLOOR, TRIM, ACC = "obsidian", "polished_blackstone_bricks", "polished_blackstone_brick", "crying_obsidian"
LIGHT = "pearlescent_froglight"
PILLAR = "polished_blackstone_wall"
MOBS = [("wither_skeleton", 3), ("blaze", 1), ("stray", 2)]
ST = "edge"

COLOURS = ["red", "blue", "yellow", "lime", "purple", "white", "orange", "cyan"]
WALL_PROPS = dict(up=True, north="none", south="none", east="none", west="none", waterlogged=False)


def gate(name, x0, x1, z0, z1, y0, y1, block, closed=True):
    return {"name": name, "box": [x0, y0, z0, x1, y1, z1], "block": block, "closed": closed}


def build_citadel(seed=31171000):
    r = random.Random(seed)
    t = Template(X, Y, Z)
    layout = {"size": [X, Y, Z], "gates": [], "levers": [], "code": [], "regions": {}, "waves": []}

    t.fill(0, F + 1, 0, X - 1, Y - 1, Z - 1, "air")
    t.fill(0, 0, 0, X - 1, 1, Z - 1, "obsidian")
    t.fill(0, F, 0, X - 1, F, Z - 1, FLOOR)
    for x in range(X):
        for z in range(Z):
            if x in (0, X - 1) or z in (0, Z - 1):
                for y in range(F, 24):
                    b._put(t, x, y, z, WALL)
                if (x + z) % 6 == 0:
                    t.set(x, 24, z, "polished_blackstone_brick_wall", **WALL_PROPS)
    t.fill(1, 23, 1, X - 2, 23, Z - 2, "blackstone")
    for x in range(6, X - 4, 10):
        for z in (6, Z - 7):
            t.set(x, 24, z, "end_rod", facing="up")
    for x in range(1, X - 1):
        for z in (1, Z - 2):
            t.set(x, F + 7, z, ACC)
            if x % 6 == 3:
                t.set(x, F + 4, z, LIGHT)

    def cross_wall(x, z0=1, z1=Z - 2, top=22):
        for z in range(z0, z1 + 1):
            for y in range(F + 1, top + 1):
                b._put(t, x, y, z, WALL)

    def opening(x, y0, y1, z0=MID - 2, z1=MID + 2):
        for z in range(z0, z1 + 1):
            for y in range(y0, y1 + 1):
                t.set(x, y, z, "air")

    # ---------------- A: entrance hall (x 0..22)
    opening(0, F + 1, F + 6)
    layout["gates"].append(gate("seal", 3, 3, MID - 2, MID + 2, F + 1, F + 6, "reinforced_deepslate", closed=False))
    for x in range(4, 22, 4):
        for z in (MID - 6, MID + 6):
            b.column(t, x, z, F + 1, F + 10, PILLAR, base=ACC, cap=LIGHT, **WALL_PROPS)
    for z in range(MID - 2, MID + 3):
        for x in range(1, 22):
            t.set(x, F, z, "crying_obsidian" if z == MID else "polished_blackstone")
    t.sign(21, F + 3, MID - 3, "west", [{"text": "World's Edge", "color": "dark_purple", "bold": True},
                                         {"text": "Mjolnir sleeps here.", "color": "gray"},
                                         {"text": "Only one may", "color": "gray"},
                                         {"text": "ever lift it.", "color": "gray"}], wood="crimson")
    layout["regions"]["inside"] = [1, F, 1, X - 2, 23, Z - 2]
    layout["regions"]["seal_trigger"] = [7, F, 1, 21, F + 10, Z - 2]
    cross_wall(22)
    opening(22, F + 1, F + 5)

    # ---------------- B: the lever code, four of eight (x 22..48)
    code = sorted(r.sample(range(8), 4))
    layout["code"] = code
    for i in range(8):
        lx = 24 + i * 3
        t.set(lx, F + 2, 2, ACC)
        t.set(lx, F + 2, 3, "lever", face="wall", facing="south", powered=False)
        t.set(lx, F + 4, 2, f"{COLOURS[i]}_wool")
        t.set(lx, F + 5, 2, LIGHT)
        layout["levers"].append([lx, F + 2, 3])
    # The clue is split: two colours framed on the far wall, two hidden behind the pillars in the room's corners.
    for k, i in enumerate(code[:2]):
        cx = 31 + k * 8
        for dx in (-1, 0, 1):
            for dy in (0, 1, 2):
                t.set(cx + dx, F + 3 + dy, Z - 3, ACC if (dx, dy) != (0, 1) else f"{COLOURS[i]}_wool")
    for (hx, hz), i in zip([(24, Z - 3), (46, Z - 3)], code[2:]):
        t.set(hx, F + 1, hz, f"{COLOURS[i]}_wool")
        t.set(hx, F + 2, hz, f"{COLOURS[i]}_carpet")
    for hx in (25, 45):
        b.column(t, hx, Z - 4, F + 1, F + 8, PILLAR, base=ACC, cap=LIGHT, **WALL_PROPS)
    t.sign(35, F + 2, Z - 3, "north", [{"text": "Four hues hold", "color": "gold"}, {"text": "the edge of the world.", "color": "gold"},
                                       {"text": "Two stand proud,", "color": "gray"}, {"text": "two hide low.", "color": "gray"}])
    b.mob_spawner(t, 35, F + 1, MID + 8, MOBS, ST)
    layout["regions"]["levers"] = [23, F, 1, 47, F + 10, Z - 2]
    TOP = F + 7
    for k in range(1, 8):
        for z in range(MID - 2, MID + 3):
            if k > 1:
                t.fill(40 + k, F + 1, z, 40 + k, F + k - 1, z, WALL)
            b.stairs(t, 40 + k, F + k, z, TRIM, "west")
    cross_wall(48)
    layout["gates"].append(gate("code", 48, 48, MID - 2, MID + 2, TOP + 1, TOP + 4, "reinforced_deepslate"))

    # ---------------- C: parkour over lava (x 49..88)
    for x in range(49, 89):
        for z in range(1, Z - 1):
            t.set(x, F, z, "lava")
            for y in range(F + 1, TOP + 6):
                t.set(x, y, z, "air")
    t.fill(49, F, MID - 2, 51, TOP, MID + 2, WALL)
    x, z, y = 51, MID, TOP
    blocks = ["crying_obsidian", "gilded_blackstone", "chiseled_polished_blackstone", "magma_block", "obsidian"]
    while x < 81:
        # Every jump can be made, none is easy: 3 forward is common, sideways steps, up only on the short ones.
        dx = r.choice([2, 3, 3, 3])
        dz = r.choice([-2, -1, 0, 1, 2]) if dx == 2 else r.choice([-1, 0, 1])
        dz = max(4 - z, min(Z - 5 - z, dz))
        dy = r.choice([-1, 0, 0, 1]) if dx + abs(dz) <= 3 else r.choice([-1, 0])
        x, z, y = x + dx, z + dz, max(TOP - 2, min(TOP + 1, y + dy))
        t.set(x, y, z, r.choice(blocks))
    while y < TOP:
        x, y = x + 2, y + 1
        t.set(x, y, z, "chiseled_polished_blackstone")
    t.fill(min(x + 1, 88), F, 1, 88, TOP, Z - 2, WALL)
    t.fill(min(x + 1, 88), TOP + 1, 1, 88, TOP + 5, Z - 2, "air")
    b.column(t, 66, Z - 6, F + 1, TOP + 3, WALL)
    b.mob_spawner(t, 66, TOP + 4, Z - 6, [("breeze", 1)], ST)
    b.column(t, 72, 5, F + 1, TOP + 3, WALL)
    b.mob_spawner(t, 72, TOP + 4, 5, [("blaze", 1)], ST)
    for x in range(54, 84, 7):
        for y in range(F + 6, F + 18):
            t.set(x, y, 1, "lava")
            t.set(x, y, 2, "tinted_glass")
    layout["regions"]["parkour"] = [49, F, 1, 84, TOP + 6, Z - 2]

    # ---------------- D: down the stairs into the maze
    cross_wall(89)
    steps = TOP - F
    for k in range(steps + 1):
        xx = 89 + k
        for z in range(MID - 1, MID + 2):
            for y in range(F + 1, TOP + 6):
                t.set(xx, y, z, "air")
            top = TOP - k
            if top > F:
                t.fill(xx, F + 1, z, xx, top, z, WALL)
                b.stairs(t, xx, top, z, TRIM, "west")
    for xx in range(89, 89 + steps + 1):
        for z in (MID - 2, MID + 2):
            for y in range(F + 1, TOP + 6):
                b._put(t, xx, y, z, WALL)
        t.set(xx, TOP + 6, MID, "soul_lantern", hanging=True, waterlogged=False)
    cells = 11
    MX0 = 89 + steps + 1
    MX1 = MX0 + 1 + cells * 3
    t.fill(MX0, F + 1, 1, MX1, F + 4, Z - 2, "blackstone")
    t.fill(MX0, F + 5, 1, MX1, F + 5, Z - 2, "blackstone")
    grid = [[False] * cells for _ in range(cells)]
    stack = [(0, cells // 2)]
    grid[0][cells // 2] = True

    def carve_cell(i, j):
        x0, z0 = MX0 + 1 + i * 3, 1 + j * 3
        t.fill(x0, F + 1, z0, x0 + 1, F + 4, z0 + 1, "air")

    carve_cell(0, cells // 2)
    while stack:
        i, j = stack[-1]
        nb = [(i + di, j + dj, di, dj) for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1))
              if 0 <= i + di < cells and 0 <= j + dj < cells and not grid[i + di][j + dj]]
        if not nb:
            stack.pop()
            continue
        ni, nj, di, dj = r.choice(nb)
        grid[ni][nj] = True
        carve_cell(ni, nj)
        x0, z0 = MX0 + 1 + i * 3, 1 + j * 3
        if di:
            t.fill(x0 + (2 if di > 0 else -1), F + 1, z0, x0 + (2 if di > 0 else -1), F + 4, z0 + 1, "air")
        else:
            t.fill(x0, F + 1, z0 + (2 if dj > 0 else -1), x0 + 1, F + 4, z0 + (2 if dj > 0 else -1), "air")
        stack.append((ni, nj))
    t.fill(MX0, F + 1, MID - 1, MX0, F + 4, MID + 1, "air")
    t.fill(MX0 + 1, F + 1, MID - 1, MX0 + 2, F + 4, MID, "air")
    t.fill(MX1, F + 1, MID - 1, MX1, F + 4, MID, "air")
    # Darker than the Tempest Keep's: only a few lights.
    for i in range(1, cells, 5):
        for j in range(2, cells, 6):
            t.set(MX0 + 1 + i * 3, F + 5, 1 + j * 3, "shroomlight")
    cx, cz = MX0 + 1 + (cells // 2) * 3, 1 + (cells // 2) * 3
    t.set(cx, F + 1, cz, ACC)
    t.set(cx, F + 2, cz, "lever", face="floor", facing="north", powered=False)
    layout["maze_lever"] = [cx, F + 2, cz]
    for (si, sj) in [(3, 2), (9, 10), (10, 3), (6, 8)]:
        b.mob_spawner(t, MX0 + 1 + si * 3, F + 1, 1 + sj * 3, MOBS, ST)
    layout["regions"]["maze"] = [MX0, F, 1, MX1, F + 5, Z - 2]
    cross_wall(MX1 + 1)
    layout["gates"].append(gate("maze", MX1 + 1, MX1 + 1, MID - 2, MID + 2, F + 1, F + 5, "reinforced_deepslate"))
    t.fill(MX1, F + 1, MID - 2, MX1, F + 4, MID + 2, "air")

    # ---------------- E: the Trial Hall (three waves; the gate opens when the last one falls)
    T0 = MX1 + 2
    T1 = T0 + 25
    for x in range(T0, T1):
        for z in range(1, Z - 1):
            t.set(x, F, z, "chiseled_polished_blackstone" if (x + z) % 7 == 0 else FLOOR)
    for (px, pz) in [(T0 + 5, MID - 9), (T0 + 5, MID + 9), (T0 + 19, MID - 9), (T0 + 19, MID + 9), (T0 + 12, MID)]:
        b.column(t, px, pz, F + 1, F + 14, PILLAR, base=ACC, cap=LIGHT, **WALL_PROPS)
    t.sign(T0 + 1, F + 3, MID - 3, "east", [{"text": "The Trial", "color": "red", "bold": True},
                                             {"text": "Three waves.", "color": "gray"},
                                             {"text": "No way back.", "color": "gray"}], wood="crimson")
    layout["regions"]["trial"] = [T0, F, 1, T1 - 1, F + 14, Z - 2]
    # Where each wave comes from: the four corners and the far end.
    layout["wave_spots"] = [[T0 + 3, F + 1, 4], [T0 + 3, F + 1, Z - 5], [T1 - 3, F + 1, 4], [T1 - 3, F + 1, Z - 5], [T1 - 2, F + 1, MID]]
    layout["waves"] = [
        [["wither_skeleton", 4], ["blaze", 2]],
        [["vindicator", 3], ["evoker", 1], ["wither_skeleton", 3]],
        [["piglin_brute", 3], ["ravager", 1], ["blaze", 3], ["evoker", 1]],
    ]
    cross_wall(T1)
    layout["gates"].append(gate("trial", T1, T1, MID - 2, MID + 2, F + 1, F + 5, "reinforced_deepslate"))
    A0 = T1 + 1

    # ---------------- F: the arena
    ax, az = A0 + 15, MID
    for x in range(A0, A0 + 31):
        for z in range(1, Z - 1):
            for y in range(F + 1, 23):
                t.set(x, y, z, "air")
            d = ((x - ax) ** 2 + (z - az) ** 2) ** 0.5
            t.set(x, F, z, "crying_obsidian" if d < 3 else ("gilded_blackstone" if int(d) % 4 == 0 else FLOOR))
    for k in range(12):
        a = k * math.pi * 2 / 12
        px, pz = int(round(ax + 12.5 * math.cos(a))), int(round(az + 12.5 * math.sin(a)))
        b.column(t, px, pz, F + 1, F + 12, PILLAR, base=ACC, cap="end_rod", **WALL_PROPS)
        t.set(px, F + 6, pz, LIGHT)
    layout["boss_spawn"] = [ax, F + 1, az]
    layout["regions"]["arena"] = [A0 + 1, F, 1, A0 + 30, 22, Z - 2]
    V = A0 + 31
    cross_wall(V)
    layout["gates"].append(gate("vault", V, V, MID - 1, MID + 1, F + 1, F + 4, "reinforced_deepslate"))

    # ---------------- G: the vault and the way out
    assert V + 12 < X - 1, "the vault doesn't fit"
    for x in range(V + 1, X - 1):
        for z in range(MID - 5, MID + 6):
            t.set(x, F, z, "gold_block" if (x + z) % 2 else "crying_obsidian")
    vc = V + 5
    t.fill(vc - 1, F + 1, MID - 1, vc + 1, F + 1, MID + 1, ACC)
    t.set(vc, F + 2, MID, "chest", facing="west", type="single", waterlogged=False)
    layout["vault_chest"] = [vc, F + 2, MID]
    for (x, z) in [(V + 2, MID - 4), (V + 2, MID + 4), (V + 8, MID - 4), (V + 8, MID + 4)]:
        b.column(t, x, z, F + 1, F + 6, PILLAR, base=ACC, cap=LIGHT, **WALL_PROPS)
    layout["gates"].append(gate("exit", X - 1, X - 1, MID - 1, MID + 1, F + 1, F + 4, "reinforced_deepslate"))

    for g in layout["gates"]:
        x0, y0, z0, x1, y1, z1 = g["box"]
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    t.set(x, y, z, g["block"] if g["closed"] else "air")
    return t, layout


def write_all():
    t, layout = build_citadel()
    b.write("structure/edge/citadel.nbt", t.to_nbt())
    b.write("edge/layout.json", layout)
    return t, layout


if __name__ == "__main__":
    t, layout = write_all()
    print("edge citadel", t.size, "code", layout["code"], "gates", len(layout["gates"]))
