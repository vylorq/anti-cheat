"""The Tempest Keep: one huge fortress the owner places (/owner tempest place), the only home of the Stormbreaker.

Six sections in a row along +x: an entrance hall that seals behind you, a lever-code room, a parkour run over a pit
(a breeze spawner tries to blow you off), a dark maze with a lever at its heart, the Tempest Lord's arena (locks while
he lives) and the vault. The mod (TempestKeep.java) works the gates, the puzzles and the boss from layout.json, which
this writes next to the template.
"""
import random

import build as b
from build import Template, S, C, L, I, B

F = 2                      # floor height (0..1 is foundation)
X, Y, Z = 178, 34, 41
MID = 20                   # the keep's centre line (z)
WALL, FLOOR, TRIM, ACC = "deepslate_bricks", "polished_deepslate", "deepslate_brick", "oxidized_cut_copper"
LIGHT = "sea_lantern"
MOBS = [("stray", 2), ("husk", 2), ("wither_skeleton", 1)]
ST = "tempest"

COLOURS = ["red", "blue", "yellow", "lime", "purple", "white"]


def gate(name, x0, x1, z0, z1, y0, y1, block, closed=True):
    return {"name": name, "box": [x0, y0, z0, x1, y1, z1], "block": block, "closed": closed}


def build_keep(seed=31170900):
    r = random.Random(seed)
    t = Template(X, Y, Z)
    layout = {"size": [X, Y, Z], "gates": [], "levers": [], "code": [], "regions": {}}

    # Everything inside is cleared first (hills and caves where it's placed must not show through), then built.
    t.fill(0, F + 1, 0, X - 1, Y - 1, Z - 1, "air")
    # Foundation, floor and outer shell.
    t.fill(0, 0, 0, X - 1, 1, Z - 1, "cobbled_deepslate")
    t.fill(0, F, 0, X - 1, F, Z - 1, FLOOR)
    for x in range(X):
        for z in range(Z):
            if x in (0, X - 1) or z in (0, Z - 1):
                for y in range(F, 24):
                    b._put(t, x, y, z, WALL)
                if (x + z) % 6 == 0:
                    t.set(x, 24, z, "deepslate_brick_wall", up=True, north="none", south="none", east="none", west="none",
                          waterlogged=False)
    # Roof with lightning rods.
    t.fill(1, 23, 1, X - 2, 23, Z - 2, "deepslate_tiles")
    for x in range(6, X - 4, 10):
        for z in (6, Z - 7):
            t.set(x, 24, z, "lightning_rod", facing="up", powered=False, waterlogged=False)
    # Copper bands and lights along the outer walls, inside.
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
            b.column(t, x, z, F + 1, F + 10, "polished_deepslate_wall", base=ACC, cap=LIGHT,
                     up=True, north="none", south="none", east="none", west="none", waterlogged=False)
    for z in range(MID - 2, MID + 3):
        for x in range(1, 22):
            t.set(x, F, z, "dark_prismarine" if z == MID else "prismarine_bricks")
    t.sign(21, F + 3, MID - 3, "west", [{"text": "The Tempest Keep", "color": "aqua", "bold": True},
                                         {"text": "Only one may ever", "color": "gray"},
                                         {"text": "claim the Stormbreaker", "color": "gray"}], wood="dark_oak")
    layout["regions"]["inside"] = [1, F, 1, X - 2, 23, Z - 2]
    layout["regions"]["seal_trigger"] = [7, F, 1, 21, F + 10, Z - 2]
    cross_wall(22)
    opening(22, F + 1, F + 5)

    # ---------------- B: the lever code (x 22..44)
    code = sorted(r.sample(range(6), 3))
    layout["code"] = code
    for i in range(6):
        lx = 25 + i * 3
        t.set(lx, F + 2, 2, ACC)
        t.set(lx, F + 2, 3, "lever", face="wall", facing="south", powered=False)
        t.set(lx, F + 4, 2, f"{COLOURS[i]}_wool")
        t.set(lx, F + 5, 2, LIGHT)
        layout["levers"].append([lx, F + 2, 3])
    # The clue: the three colours, framed on the far wall, and a riddle.
    for k, i in enumerate(code):
        cx = 29 + k * 4
        for dx in (-1, 0, 1):
            for dy in (0, 1, 2):
                t.set(cx + dx, F + 3 + dy, Z - 3, ACC if (dx, dy) != (0, 1) else f"{COLOURS[i]}_wool")
    t.sign(33, F + 2, Z - 3, "north", [{"text": "Three hues hold", "color": "gold"}, {"text": "the storm at bay.", "color": "gold"},
                                       {"text": "Choose wrong and", "color": "red"}, {"text": "it answers.", "color": "red"}])
    b.mob_spawner(t, 33, F + 1, MID + 8, MOBS, ST)
    layout["regions"]["levers"] = [23, F, 1, 43, F + 10, Z - 2]
    TOP = F + 7                 # the parkour route: blocks at TOP, you stand on TOP + 1
    # A stair up to the gate, which opens at the route's height.
    for k in range(1, 8):
        for z in range(MID - 2, MID + 3):
            if k > 1:
                t.fill(36 + k, F + 1, z, 36 + k, F + k - 1, z, WALL)
            b.stairs(t, 36 + k, F + k, z, TRIM, "west")
    cross_wall(44)
    layout["gates"].append(gate("code", 44, 44, MID - 2, MID + 2, TOP + 1, TOP + 4, "polished_blackstone_bricks"))

    # ---------------- C: parkour over the pit (x 45..84)
    for x in range(45, 85):
        for z in range(1, Z - 1):
            t.set(x, F, z, "water")
            for y in range(F + 1, TOP + 6):
                t.set(x, y, z, "air")
    # Start ledge, with a ladder up from the water to it.
    t.fill(45, F, MID - 2, 47, TOP, MID + 2, WALL)
    for y in range(F + 1, TOP + 1):
        t.set(48, y, MID, "ladder", facing="east", waterlogged=False)
        t.set(47, y, MID, WALL)
    # The jumps: single blocks, gaps of 2-3, the line wandering across the room and up and down.
    x, z, y = 47, MID, TOP
    blocks = ["oxidized_copper", "weathered_cut_copper", "chiseled_deepslate", "sea_lantern", "copper_grate"]
    while x < 77:
        # Every jump can be made, none is easy: 2-3 forward with a sideways step, up only on the short ones.
        dx = r.choice([2, 3, 3])
        dz = r.choice([-2, -1, 0, 1, 2]) if dx == 2 else r.choice([-1, 0, 1])
        dz = max(4 - z, min(Z - 5 - z, dz))
        dy = r.choice([-1, 0, 0, 1]) if dx + abs(dz) <= 3 else r.choice([-1, 0])
        x, z, y = x + dx, z + dz, max(TOP - 2, min(TOP + 1, y + dy))
        t.set(x, y, z, r.choice(blocks))
    # The last jump lands on the end ledge (route height).
    while y < TOP:
        x, y = x + 2, y + 1
        t.set(x, y, z, "chiseled_deepslate")
    # End ledge and the way on, up at the route's height.
    t.fill(min(x + 1, 84), F, 1, 84, TOP, Z - 2, WALL)
    t.fill(min(x + 1, 84), TOP + 1, 1, 84, TOP + 5, Z - 2, "air")
    # A breeze up on a pillar in the middle, to blow people off.
    b.column(t, 64, Z - 6, F + 1, TOP + 3, WALL)
    b.mob_spawner(t, 64, TOP + 4, Z - 6, [("breeze", 1)], ST)
    # Lava falling behind glass on the side walls, for looks.
    for x in range(50, 80, 7):
        for y in range(F + 6, F + 18):
            t.set(x, y, 1, "lava")
            t.set(x, y, 2, "tinted_glass")
    layout["regions"]["parkour"] = [45, F, 1, 80, TOP + 6, Z - 2]

    # ---------------- D: down the stairs into the maze
    # From the ledge (standing at TOP + 2) a stair goes down through x 85..92 to the maze floor.
    cross_wall(85)
    steps = TOP - F
    for k in range(steps + 1):
        xx = 85 + k
        for z in range(MID - 1, MID + 2):
            for y in range(F + 1, TOP + 6):
                t.set(xx, y, z, "air")
            top = TOP - k
            if top > F:
                t.fill(xx, F + 1, z, xx, top, z, WALL)
                b.stairs(t, xx, top, z, TRIM, "west")
    for xx in range(85, 85 + steps + 1):
        for z in (MID - 2, MID + 2):
            for y in range(F + 1, TOP + 6):
                b._put(t, xx, y, z, WALL)
        t.set(xx, TOP + 6, MID, "soul_lantern", hanging=True, waterlogged=False)
    cells = 11
    MX0 = 85 + steps + 1
    MX1 = MX0 + 1 + cells * 3
    t.fill(MX0, F + 1, 1, MX1, F + 4, Z - 2, "deepslate_tiles")
    t.fill(MX0, F + 5, 1, MX1, F + 5, Z - 2, "deepslate_tiles")
    grid = [[False] * cells for _ in range(cells)]
    sys_stack = [(0, cells // 2)]
    grid[0][cells // 2] = True

    def carve_cell(i, j):
        x0, z0 = MX0 + 1 + i * 3, 1 + j * 3
        t.fill(x0, F + 1, z0, x0 + 1, F + 4, z0 + 1, "air")

    carve_cell(0, cells // 2)
    while sys_stack:
        i, j = sys_stack[-1]
        nb = [(i + di, j + dj, di, dj) for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1))
              if 0 <= i + di < cells and 0 <= j + dj < cells and not grid[i + di][j + dj]]
        if not nb:
            sys_stack.pop()
            continue
        ni, nj, di, dj = r.choice(nb)
        grid[ni][nj] = True
        carve_cell(ni, nj)
        x0, z0 = MX0 + 1 + i * 3, 1 + j * 3
        if di:
            t.fill(x0 + (2 if di > 0 else -1), F + 1, z0, x0 + (2 if di > 0 else -1), F + 4, z0 + 1, "air")
        else:
            t.fill(x0, F + 1, z0 + (2 if dj > 0 else -1), x0 + 1, F + 4, z0 + (2 if dj > 0 else -1), "air")
        sys_stack.append((ni, nj))
    # Ways in and out.
    t.fill(MX0, F + 1, MID - 1, MX0, F + 4, MID + 1, "air")
    t.fill(MX0 + 1, F + 1, MID - 1, MX0 + 2, F + 4, MID, "air")
    t.fill(MX1, F + 1, MID - 1, MX1, F + 4, MID, "air")
    # A few soul lights, mostly dark.
    for i in range(0, cells, 4):
        for j in range(1, cells, 5):
            t.set(MX0 + 1 + i * 3, F + 5, 1 + j * 3, "shroomlight")
    # The lever at the heart.
    cx, cz = MX0 + 1 + (cells // 2) * 3, 1 + (cells // 2) * 3
    t.set(cx, F + 1, cz, ACC)
    t.set(cx, F + 2, cz, "lever", face="floor", facing="north", powered=False)
    layout["maze_lever"] = [cx, F + 2, cz]
    b.mob_spawner(t, MX0 + 1 + 3 * 3, F + 1, 1 + 2 * 3, MOBS, ST)
    b.mob_spawner(t, MX0 + 1 + 9 * 3, F + 1, 1 + 10 * 3, MOBS, ST)
    b.mob_spawner(t, MX0 + 1 + 10 * 3 + 1, F + 1, 1 + 3 * 3 + 1, MOBS, ST)
    layout["regions"]["maze"] = [MX0, F, 1, MX1, F + 5, Z - 2]
    cross_wall(MX1 + 1)
    layout["gates"].append(gate("maze", MX1 + 1, MX1 + 1, MID - 2, MID + 2, F + 1, F + 5, "polished_blackstone_bricks"))
    t.fill(MX1, F + 1, MID - 2, MX1, F + 4, MID + 2, "air")
    A0 = MX1 + 2

    # ---------------- E: the arena (x 126..156)
    ax, az = A0 + 15, MID
    for x in range(A0, A0 + 31):
        for z in range(1, Z - 1):
            for y in range(F + 1, 23):
                t.set(x, y, z, "air")
            d = ((x - ax) ** 2 + (z - az) ** 2) ** 0.5
            t.set(x, F, z, "chiseled_deepslate" if d < 3 else ("oxidized_cut_copper" if int(d) % 4 == 0 else FLOOR))
    for k in range(10):
        a = k * 3.14159 * 2 / 10
        px, pz = int(round(ax + 12.5 * __import__("math").cos(a))), int(round(az + 12.5 * __import__("math").sin(a)))
        b.column(t, px, pz, F + 1, F + 12, "polished_deepslate_wall", base=ACC, cap="lightning_rod",
                 up=True, north="none", south="none", east="none", west="none", waterlogged=False)
        t.set(px, F + 13, pz, "lightning_rod", facing="up", powered=False, waterlogged=False)
        t.set(px, F + 6, pz, LIGHT)
    # The maze gate also locks the arena while its lord lives (TempestKeep.java).
    layout["boss_spawn"] = [ax, F + 1, az]
    layout["regions"]["arena"] = [A0 + 1, F, 1, A0 + 30, 22, Z - 2]
    V = A0 + 31
    cross_wall(V)
    layout["gates"].append(gate("vault", V, V, MID - 1, MID + 1, F + 1, F + 4, "reinforced_deepslate"))

    # ---------------- F: the vault (x 158..168) and the way out
    for x in range(V + 1, X - 1):
        for z in range(MID - 5, MID + 6):
            t.set(x, F, z, "gold_block" if (x + z) % 2 else "polished_deepslate")
    vc = V + 5
    t.fill(vc - 1, F + 1, MID - 1, vc + 1, F + 1, MID + 1, ACC)
    t.set(vc, F + 2, MID, "chest", facing="west", type="single", waterlogged=False)
    layout["vault_chest"] = [vc, F + 2, MID]
    for (x, z) in [(V + 2, MID - 4), (V + 2, MID + 4), (V + 8, MID - 4), (V + 8, MID + 4)]:
        b.column(t, x, z, F + 1, F + 6, "polished_deepslate_wall", base=ACC, cap=LIGHT,
                 up=True, north="none", south="none", east="none", west="none", waterlogged=False)
    layout["gates"].append(gate("exit", X - 1, X - 1, MID - 1, MID + 1, F + 1, F + 4, "reinforced_deepslate"))
    t.fill(X - 1, F + 1, MID - 1, X - 1, F + 4, MID + 1, "reinforced_deepslate")

    # Closed gates start closed in the template.
    for g in layout["gates"]:
        x0, y0, z0, x1, y1, z1 = g["box"]
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    t.set(x, y, z, g["block"] if g["closed"] else "air")
    return t, layout


def write_all():
    t, layout = build_keep()
    b.write("structure/tempest/keep.nbt", t.to_nbt())
    b.write("tempest/layout.json", layout)
    return t, layout


if __name__ == "__main__":
    t, layout = write_all()
    print("tempest keep", t.size, "code", layout["code"], "gates", len(layout["gates"]))
