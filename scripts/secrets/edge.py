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
X, Y, Z = 240, 48, 51
MID = 25                   # the citadel's centre line (z)
ROOF = 33                  # the roof's height; the towers and spires rise above it
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
                for y in range(F, ROOF + 1):
                    b._put(t, x, y, z, WALL)
                # Battlements: merlons every other block, gold-capped at the bays.
                if (x + z) % 2 == 0:
                    t.set(x, ROOF + 1, z, "polished_blackstone_bricks")
                    if (x + z) % 8 == 0:
                        t.set(x, ROOF + 2, z, "gilded_blackstone")
                    else:
                        t.set(x, ROOF + 2, z, "polished_blackstone_brick_slab", type="bottom", waterlogged=False)
    t.fill(1, ROOF, 1, X - 2, ROOF, Z - 2, "blackstone")
    # Outside: gold bands, crying-obsidian ribs between tall stained-glass windows.
    for x in range(X):
        for z in (0, Z - 1):
            t.set(x, F + 9, z, "gilded_blackstone")
            t.set(x, ROOF - 1, z, "gilded_blackstone")
            if x % 10 == 0:
                for y in range(F + 1, ROOF):
                    t.set(x, y, z, ACC)
            elif x % 10 in (4, 5, 6) and F + 12 <= ROOF - 4:
                for y in range(F + 12, ROOF - 3):
                    t.set(x, y, z, "purple_stained_glass" if x % 10 != 5 else "magenta_stained_glass")
                t.set(x, ROOF - 3, z, "polished_blackstone_bricks")
    for z in range(Z):
        for x in (0, X - 1):
            t.set(x, F + 9, z, "gilded_blackstone")
            t.set(x, ROOF - 1, z, "gilded_blackstone")
    # Inside: crying-obsidian bands and lights round every wall.
    for x in range(1, X - 1):
        for z in (1, Z - 2):
            t.set(x, F + 7, z, ACC)
            t.set(x, F + 17, z, ACC)
            if x % 6 == 3:
                t.set(x, F + 4, z, LIGHT)
                t.set(x, F + 14, z, LIGHT)

    def tower(x0, z0, size=7, top=Y - 4):
        """A corner tower: solid to the roof, then a hollow crown with windows and a spire of crying obsidian."""
        for x in range(x0, x0 + size):
            for z in range(z0, z0 + size):
                edge = x in (x0, x0 + size - 1) or z in (z0, z0 + size - 1)
                for y in range(ROOF, top):
                    if edge:
                        corner = x in (x0, x0 + size - 1) and z in (z0, z0 + size - 1)
                        window = not corner and (y - ROOF) % 6 in (2, 3) and (x - x0 == size // 2 or z - z0 == size // 2)
                        t.set(x, y, z, "purple_stained_glass" if window else (ACC if corner else WALL))
                t.set(x, top, z, "polished_blackstone_bricks")
                if edge and (x + z) % 2 == 0:
                    t.set(x, top + 1, z, "gilded_blackstone")
        cx, cz = x0 + size // 2, z0 + size // 2
        for k, y in enumerate(range(top + 1, min(Y, top + 4))):
            t.set(cx, y, cz, ACC if k < 2 else "end_rod", **({"facing": "up"} if k >= 2 else {}))
        t.set(cx, ROOF + 1, cz, LIGHT)

    for (tx, tz) in [(0, 0), (X - 7, 0), (0, Z - 7), (X - 7, Z - 7)]:
        tower(tx, tz)
    # Spires along the roof.
    for x in range(20, X - 20, 20):
        for z in (8, Z - 9):
            for k, w in enumerate((2, 1, 0)):
                for dx in range(-w, w + 1):
                    for dz in range(-w, w + 1):
                        t.set(x + dx, ROOF + 1 + k, z + dz, "polished_blackstone_bricks" if w else ACC)
            t.set(x, ROOF + 4, z, "end_rod", facing="up")
            t.set(x, ROOF + 5, z, "end_rod", facing="up")

    def chandelier(x, z, drop=6):
        """A post from the roof down, a cross of lights and lanterns hanging under it."""
        for y in range(ROOF - drop, ROOF):
            t.set(x, y, z, PILLAR, **WALL_PROPS)
        y = ROOF - drop - 1
        t.set(x, y, z, LIGHT)
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            t.set(x + dx, y, z + dz, "gilded_blackstone")
            t.set(x + dx, y - 1, z + dz, "soul_lantern", hanging=True, waterlogged=False)

    def cross_wall(x, z0=1, z1=Z - 2, top=ROOF - 1):
        for z in range(z0, z1 + 1):
            for y in range(F + 1, top + 1):
                b._put(t, x, y, z, WALL)

    def opening(x, y0, y1, z0=MID - 2, z1=MID + 2):
        for z in range(z0, z1 + 1):
            for y in range(y0, y1 + 1):
                t.set(x, y, z, "air")

    # ---------------- A: entrance hall (x 0..22)
    # A grand arch round the door: gold and crying obsidian, a keystone of light.
    for z in range(MID - 5, MID + 6):
        for y in range(F + 1, F + 12):
            ring = abs(z - MID) in (4, 5) or y in (F + 10, F + 11)
            if ring:
                t.set(0, y, z, "gilded_blackstone" if (y + z) % 2 else ACC)
    t.set(0, F + 11, MID, LIGHT)
    opening(0, F + 1, F + 6)
    layout["gates"].append(gate("seal", 3, 3, MID - 2, MID + 2, F + 1, F + 6, "reinforced_deepslate", closed=False))
    for x in range(4, 22, 4):
        for z in (MID - 6, MID + 6):
            b.column(t, x, z, F + 1, F + 10, PILLAR, base=ACC, cap=LIGHT, **WALL_PROPS)
    for z in range(MID - 2, MID + 3):
        for x in range(1, 22):
            t.set(x, F, z, "crying_obsidian" if z == MID else "polished_blackstone")
    for z in range(2, Z - 2):
        for x in range(8, 22):
            if abs(z - MID) > 2 and (x + z) % 4 == 0:
                t.set(x, F, z, "chiseled_polished_blackstone")
    chandelier(14, MID)
    t.sign(21, F + 3, MID - 3, "west", [{"text": "World's Edge", "color": "dark_purple", "bold": True},
                                         {"text": "Mjolnir sleeps here.", "color": "gray"},
                                         {"text": "Only one may", "color": "gray"},
                                         {"text": "ever lift it.", "color": "gray"}], wood="crimson")
    # The offering: four hoppers by the inner door, each wanting its own item, in order (I to IV). The door opens when
    # the fourth is given; a wrong item, or the right one out of turn, is thrown back with lightning.
    for z in range(1, Z - 1):
        for y in range(F + 1, F + 11):
            b._put(t, 6, y, z, WALL)
    layout["gates"].append(gate("entry", 6, 6, MID - 2, MID + 2, F + 1, F + 6, "reinforced_deepslate"))
    OFFER = [("nether_star", "The Wither's star"), ("heart_of_the_sea", "The sea's heart"),
             ("echo_shard", "An echo of the deep"), ("totem_of_undying", "A token of undying")]
    layout["hoppers"] = []
    layout["offering"] = [item for item, _ in OFFER]
    for k, (z, (item, clue)) in enumerate(zip([MID - 5, MID - 3, MID + 3, MID + 5], OFFER)):
        t.set(5, F + 1, z, "hopper", facing="down", enabled=True)
        t.set(5, F, z, ACC)
        layout["hoppers"].append([5, F + 1, z])
        t.sign(5, F + 2, z, "west", [{"text": ["I", "II", "III", "IV"][k], "color": "gold", "bold": True},
                                     {"text": clue, "color": "light_purple"},
                                     {"text": "Give in order", "color": "gray"}], wood="crimson")
    layout["regions"]["porch"] = [1, F, 1, 5, F + 10, Z - 2]
    layout["regions"]["inside"] = [1, F, 1, X - 2, ROOF, Z - 2]
    # Each room must be cleared, in order: this many of its mobs must die before its way on opens.
    layout["clear"] = {"levers": 8, "parkour": 6, "maze": 10}
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

    # ---------------- D: down the stairs into the maze (the gate opens once the parkour room is cleared)
    cross_wall(89)
    layout["gates"].append(gate("parkour", 89, 89, MID - 1, MID + 1, TOP + 1, TOP + 4, "reinforced_deepslate"))
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
    cells = 15
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
    # The way out: from the last column of cells through the maze's far wall.
    t.fill(MX0 + cells * 3, F + 1, MID - 1, MX1, F + 4, MID, "air")
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
    T1 = T0 + 31
    for x in range(T0, T1):
        for z in range(1, Z - 1):
            t.set(x, F, z, "chiseled_polished_blackstone" if (x + z) % 7 == 0 else FLOOR)
    for (px, pz) in [(T0 + 5, MID - 11), (T0 + 5, MID + 11), (T0 + 25, MID - 11), (T0 + 25, MID + 11), (T0 + 15, MID)]:
        b.column(t, px, pz, F + 1, F + 18, PILLAR, base=ACC, cap=LIGHT, **WALL_PROPS)
    chandelier(T0 + 10, MID - 6)
    chandelier(T0 + 20, MID + 6)
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
    ax, az = A0 + 18, MID
    for x in range(A0, A0 + 37):
        for z in range(1, Z - 1):
            for y in range(F + 1, ROOF):
                t.set(x, y, z, "air")
            d = ((x - ax) ** 2 + (z - az) ** 2) ** 0.5
            t.set(x, F, z, "crying_obsidian" if d < 3 else ("gilded_blackstone" if int(d) % 4 == 0 else FLOOR))
    for k in range(16):
        a = k * math.pi * 2 / 16
        px, pz = int(round(ax + 15.5 * math.cos(a))), int(round(az + 15.5 * math.sin(a)))
        b.column(t, px, pz, F + 1, F + 16, PILLAR, base=ACC, cap="end_rod", **WALL_PROPS)
        t.set(px, F + 6, pz, LIGHT)
        t.set(px, F + 12, pz, "gilded_blackstone")
    chandelier(ax, az, drop=9)
    # The great tower above the arena: a crown of glass and crying obsidian over the Sovereign's throne room.
    for x in range(ax - 5, ax + 6):
        for z in range(az - 5, az + 6):
            edge = abs(x - ax) == 5 or abs(z - az) == 5
            for y in range(ROOF + 1, Y - 3):
                if edge:
                    corner = abs(x - ax) == 5 and abs(z - az) == 5
                    t.set(x, y, z, ACC if corner else ("magenta_stained_glass" if (y - ROOF) % 4 != 0 else WALL))
            t.set(x, Y - 3, z, "polished_blackstone_bricks")
    for y in range(Y - 2, Y):
        t.set(ax, y, az, "end_rod", facing="up")
    t.set(ax, ROOF, az, LIGHT)
    layout["boss_spawn"] = [ax, F + 1, az]
    layout["regions"]["arena"] = [A0 + 1, F, 1, A0 + 36, ROOF - 1, Z - 2]
    V = A0 + 37
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


# ---------------------------------------------------------------- the castle round the citadel
M = 16                     # the castle grounds on every side: bank, moat, curtain wall, courtyard
CY = 72                    # the castle's full height, the great tower's roof included
STONE, ROOF_TILE, GOLD = "polished_blackstone_bricks", "purple_terracotta", "gilded_blackstone"


def castle(inner, layout):
    """Puts the citadel inside a castle: a moat round it, a crenellated curtain wall with round towers, a gatehouse with
    a drawbridge and portcullis in the west, a postern in the east, round towers with pointed roofs on the citadel's
    four corners and a great spire over the Sovereign's arena. Everything in layout.json moves with the citadel."""
    X2, Z2 = X + 2 * M, Z + 2 * M
    t = Template(X2, CY, Z2)
    for (x, y, z), v in inner.blocks.items():
        t.blocks[(x + M, y, z + M)] = v
    for (x, y, z), v in inner.nbt.items():
        t.nbt[(x + M, y, z + M)] = v
    K0x, K1x, K0z, K1z = M, M + X - 1, M, M + Z - 1
    GZ = M + MID

    def in_keep(x, z):
        return K0x <= x <= K1x and K0z <= z <= K1z

    # The grounds: a grassy bank, the moat, the curtain wall's footing and the courtyard.
    for x in range(X2):
        for z in range(Z2):
            if in_keep(x, z):
                continue
            d = min(x, z, X2 - 1 - x, Z2 - 1 - z)
            t.fill(x, 0, z, x, F - 1, z, "blackstone")
            for y in range(F + 1, F + 32):
                t.set(x, y, z, "air")
            if d <= 1:
                t.set(x, F - 1, z, "dirt")
                t.set(x, F, z, "grass_block", snowy=False)
            elif d <= 6:
                t.set(x, 0, z, "obsidian")
                t.fill(x, 1, z, x, F, z, "water", level=0)
            elif d <= 9:
                t.fill(x, 0, z, x, F + 14, z, STONE)
                t.set(x, F + 13, z, GOLD)
                if d == 7:
                    if (x + z) % 2 == 0:
                        t.set(x, F + 15, z, STONE)
                        t.set(x, F + 16, z, "polished_blackstone_brick_slab", type="bottom", waterlogged=False)
                    if (x + z) % 7 == 0:
                        t.fill(x, F + 7, z, x, F + 9, z, "tinted_glass")
            else:
                ring = min(abs(x - K0x), abs(x - K1x), abs(z - K0z), abs(z - K1z))
                t.set(x, F, z, "polished_blackstone" if ring <= 2 else ("moss_block" if (x * 7 + z * 3) % 5 else "coarse_dirt"))
                if d == 10 and (x + z) % 10 == 0:
                    b.column(t, x, z, F + 1, F + 4, PILLAR, base=STONE, **WALL_PROPS)
                    t.set(x, F + 5, z, "soul_lantern", hanging=False, waterlogged=False)
    # Stairs up to the wall walk from the courtyard, at the four inner corners.
    for (sx, sz, dx) in [(16, 10, 1), (X2 - 17, 10, -1), (16, Z2 - 11, 1), (X2 - 17, Z2 - 11, -1)]:
        for k in range(14):
            x = sx + dx * k
            t.fill(x, F + 1, sz, x, F + k, sz, STONE)
            b.stairs(t, x, F + k + 1, sz, "polished_blackstone_brick", "east" if dx > 0 else "west")

    def round_tower(cx, cz, r, y0, top, cone, skip=lambda x, y, z: False, windows=True):
        """A round tower: a ring wall with gold bands and glass slits, a crenellated top and a pointed purple roof."""
        for x in range(cx - r - 2, cx + r + 3):
            for z in range(cz - r - 2, cz + r + 3):
                dd = math.hypot(x - cx, z - cz)
                if dd <= r + 0.5:
                    for y in range(y0, top + 1):
                        if skip(x, y, z):
                            continue
                        if dd >= r - 0.9:
                            slit = windows and (y - y0) % 9 in (5, 6, 7) and (abs(x - cx) <= 0 or abs(z - cz) <= 0)
                            t.set(x, y, z, "purple_stained_glass" if slit else (GOLD if (y - y0) % 9 == 0 else STONE))
                        else:
                            t.set(x, y, z, "air" if y < top else STONE)
                    if r - 0.9 <= dd and not skip(x, top + 1, z) and (x + z) % 2 == 0:
                        t.set(x, top + 1, z, STONE)
                # The roof: one wider than the tower, narrowing to a gold point.
                for k in range(cone):
                    rr = (r + 1.5) * (1 - k / cone)
                    if dd <= rr + 0.3 and dd > rr - 1.6:
                        t.set(x, top + 2 + k, z, ROOF_TILE if k % 4 != 3 else "purple_glazed_terracotta")
        t.set(cx, top + 2 + cone, cz, GOLD)
        t.set(cx, top + 3 + cone, cz, "lightning_rod", facing="up", powered=False, waterlogged=False)
        t.set(cx, top + 1, cz, LIGHT)

    # Curtain-wall towers: the four corners and every forty blocks along the long sides.
    for cx in (8, X2 - 9):
        for cz in (8, Z2 - 9):
            round_tower(cx, cz, 5, 0, F + 22, 12)
    for cx in range(50, X2 - 40, 44):
        for cz in (8, Z2 - 9):
            round_tower(cx, cz, 4, 0, F + 19, 9)
    # The gatehouse in the west: two towers, a hall over the gate, a portcullis of chains and a drawbridge.
    for cz in (GZ - 7, GZ + 7):
        round_tower(8, cz, 4, 0, F + 24, 11)
    t.fill(5, F + 1, GZ - 4, 11, F + 20, GZ + 4, STONE)
    t.fill(6, F + 10, GZ - 3, 10, F + 19, GZ + 3, "air")
    for z in range(GZ - 4, GZ + 5):
        t.set(5, F + 20, z, GOLD)
        if z % 2 == 0:
            t.set(5, F + 21, z, STONE)
            t.set(11, F + 21, z, STONE)
        for y in (F + 12, F + 13, F + 16, F + 17):
            if abs(z - GZ) in (1, 2):
                t.set(5, y, z, "purple_stained_glass")
    t.fill(5, F + 1, GZ - 2, 11, F + 7, GZ + 2, "air")
    for z in range(GZ - 3, GZ + 4):
        for y in range(F + 1, F + 9):
            if abs(z - GZ) == 3 or y == F + 8:
                t.set(5, y, z, GOLD if (y + z) % 2 else ACC)
    t.set(5, F + 8, GZ, LIGHT)
    for z in range(GZ - 2, GZ + 3):
        t.set(7, F + 7, z, "chain", axis="y", waterlogged=False)
        t.set(7, F + 6, z, "chain", axis="y", waterlogged=False)
    for z in range(GZ - 6, GZ + 7, 12):
        for y in range(F + 9, F + 18):
            t.set(4, y, z, "purple_wool" if y > F + 10 else "yellow_wool")
            t.set(4, y, z + (1 if z < GZ else -1), "purple_wool" if y > F + 9 else "yellow_wool")
    for x in range(0, 12):
        for z in range(GZ - 2, GZ + 3):
            t.set(x, F, z, "dark_oak_planks" if x < 7 else "polished_blackstone")
    for x in (0, 6):
        for z in (GZ - 3, GZ + 3):
            b.column(t, x, z, F, F + 2, PILLAR, base=STONE, **WALL_PROPS)
            t.set(x, F + 3, z, "soul_lantern", hanging=False, waterlogged=False)
    # The postern in the east, where the vault's door lets out.
    t.fill(X2 - 10, F + 1, GZ - 1, X2 - 8, F + 5, GZ + 1, "air")
    for x in range(X2 - 10, X2):
        for z in range(GZ - 1, GZ + 2):
            t.set(x, F, z, "dark_oak_planks" if x >= X2 - 7 else "polished_blackstone")
    for z in range(GZ - 2, GZ + 3):
        for y in range(F + 1, F + 7):
            if abs(z - GZ) == 2 or y == F + 6:
                t.set(X2 - 8, y, z, GOLD if (y + z) % 2 else ACC)
    # Paths across the courtyard to the citadel's doors.
    for x in list(range(12, K0x)) + list(range(K1x + 1, X2 - 10)):
        for z in range(GZ - 2, GZ + 3):
            t.set(x, F, z, "crying_obsidian" if z == GZ else "polished_blackstone")

    # Round towers on the citadel's corners, high over its roof (the small square ones there make way).
    for (cx, cz) in [(K0x, K0z), (K1x, K0z), (K0x, K1z), (K1x, K1z)]:
        x0, z0 = (cx if cx == K0x else cx - 6), (cz if cz == K0z else cz - 6)
        t.fill(x0, ROOF + 1, z0, x0 + 6, Y - 1, z0 + 6, "air")
        round_tower(cx, cz, 5, 0, ROOF + 14, 13, skip=lambda x, y, z: in_keep(x, z) and y <= ROOF)
    # The great spire over the arena: the crown rises higher, then a pointed roof.
    ax, _, az = layout["boss_spawn"]
    ax, az = ax + M, az + M
    for x in range(ax - 5, ax + 6):
        for z in range(az - 5, az + 6):
            edge = abs(x - ax) == 5 or abs(z - az) == 5
            corner = abs(x - ax) == 5 and abs(z - az) == 5
            for y in range(Y - 3, Y + 8):
                t.set(x, y, z, (ACC if corner else ("magenta_stained_glass" if (y - ROOF) % 4 != 0 else WALL)) if edge else "air")
            t.set(x, Y + 8, z, STONE)
            if edge and (x + z) % 2 == 0:
                t.set(x, Y + 9, z, GOLD)
    for k in range(CY - Y - 12):
        w = 6 - k * 6 // (CY - Y - 12)
        for x in range(ax - w, ax + w + 1):
            for z in range(az - w, az + w + 1):
                if max(abs(x - ax), abs(z - az)) == w:
                    t.set(x, Y + 10 + k, z, ROOF_TILE if k % 4 != 3 else "purple_glazed_terracotta")
    t.set(ax, CY - 2, az, GOLD)
    t.set(ax, CY - 1, az, "lightning_rod", facing="up", powered=False, waterlogged=False)

    # Move everything in the layout with the citadel.
    def mv(p):
        return [p[0] + M, p[1], p[2] + M] + ([p[3] + M, p[4], p[5] + M] if len(p) == 6 else [])
    out = dict(layout)
    out["size"] = [X2, CY, Z2]
    out["gates"] = [dict(g, box=mv(g["box"])) for g in layout["gates"]]
    out["regions"] = {k: mv(v) for k, v in layout["regions"].items()}
    for k in ("levers", "hoppers", "wave_spots"):
        out[k] = [mv(p) for p in layout[k]]
    for k in ("maze_lever", "boss_spawn", "vault_chest"):
        out[k] = mv(layout[k])
    return t, out


def write_all():
    t, layout = castle(*build_citadel())
    b.write("structure/edge/citadel.nbt", t.to_nbt())
    b.write("edge/layout.json", layout)
    return t, layout


if __name__ == "__main__":
    t, layout = write_all()
    print("edge citadel", t.size, "code", layout["code"], "gates", len(layout["gates"]))
