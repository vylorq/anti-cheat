"""The boss and guard designs for cubes.py (see that file for the coordinate system).

Everything here is meant to look like a threat: hunched bodies with the head low and pushed forward, long arms with
claws, spikes and horns, glowing slanted eyes in shadowed sockets and jaws full of teeth.
"""
from cubes import C, Mat, Model, face, spot, stripes, chain

# ---------------------------------------------------------------------------------------------------------------------
# Materials

DEEPSLATE = Mat(["#1d1d22", "#2e2e35", "#41414a", "#575762"], "brick", veins="#2fe6f2", vein_density=0.04)
DEEPSLATE_MOSS = Mat(["#1d1d22", "#2e2e35", "#41414a", "#575762"], "brick", top=["#24401a", "#335a22", "#45752c", "#5a9036"],
                     top_rows=2, veins="#2fe6f2", vein_density=0.025)
AMETHYST = Mat(["#4a2a80", "#6d43b0", "#9a6fdc", "#d6bdff"], cell=1, mix=(0.15, 0.45, 0.3, 0.1))
BONE = Mat(["#8e8a76", "#b3af98", "#cfcbb4", "#ebe8d6"], cell=1)
BONE_DARK = Mat(["#4a463a", "#68634f", "#837d66", "#9c9680"], cell=1)
SKIN_DROWNED = Mat(["#15302b", "#21463d", "#2f5e50", "#437a66"])
DROWNED_RIBS = Mat(["#0a1714", "#15302b", "#7fa89a", "#a8cfc0"], "ribs")
CLOTH_DROWNED = Mat(["#0c1828", "#13243a", "#1c3552", "#27486a"], "bands")
PRISMARINE = Mat(["#1b403e", "#2a6560", "#3f8a7e", "#6cc0ad"], "plates")
KELP = Mat(["#13290d", "#1d3f14", "#28561a", "#367323"], "bark")
GOLD = Mat(["#7a4c0c", "#b07c18", "#d9a82c", "#ffe27a"], "plates", cell=1)
GOLD_RAW = Mat(["#7a4c0c", "#b07c18", "#d9a82c", "#ffe27a"], cell=1)
STORM_BODY = Mat(["#0e1322", "#172036", "#212c4a", "#313f66"], "scales", veins="#a8f6ff", vein_density=0.02)
STORM_WING = Mat(["#121a30", "#1a2440", "#222e50", "#2e3c66"], cell=3, veins="#a8f6ff", vein_density=0.06)
BLACKSTONE = Mat(["#120e14", "#1d181f", "#2a232c", "#3a313d"], "brick", veins="#ff7a1a", vein_density=0.07)
IRON_DARK = Mat(["#24272c", "#363a41", "#4c525a", "#6f767f"], "plates")
CHAIN = Mat(["#141518", "#24262b", "#383b42", "#555a62"], "scales")
WOOD = Mat(["#22150b", "#321f10", "#432b17", "#563820"], "bark")
WRAPS = Mat(["#5a4a31", "#7a6646", "#9a845d", "#b9a378"], "bands")
WRAPS_DARK = Mat(["#2e2619", "#43382a", "#5a4a31", "#6e5c3e"], "bands")
MUMMY_RIBS = Mat(["#1b150e", "#2e2619", "#a39070", "#c4ad80"], "ribs")
LAPIS = Mat(["#10205a", "#18307e", "#2442a6", "#3a62cc"], cell=1)
FROST_SKIN = Mat(["#1f3448", "#2c4862", "#3d5f7e", "#557c9c"])
ICE = Mat(["#2c548e", "#4372b4", "#6a98d8", "#b2d4fa"], "plates", cell=1)
ICE_RAW = Mat(["#5a8ccc", "#7eaee6", "#a8d0fa", "#e2f2ff"], cell=1)
FROST_FUR = Mat(["#6d8294", "#8fa4b6", "#b2c4d2", "#d6e2ec"], "fur")
BARK_FUR = Mat(["#17120b", "#251d12", "#352b1b", "#4a3d26"], "fur", top=["#1d3314", "#29461c", "#365c24", "#45752c"])
BARK_FUR_PLAIN = Mat(["#17120b", "#251d12", "#352b1b", "#4a3d26"], "fur")
HOOF = Mat(["#0b0906", "#15110b", "#1f1910", "#2a2216"])
THORN = Mat(["#26300f", "#3a4a16", "#52681f", "#76922f"], cell=1)
VOID = Mat(["#05060a", "#0b0d14", "#12151f", "#1b1f2e"], veins="#27d9e6", vein_density=0.05)
VOID_RIBS = Mat(["#05060a", "#0b0d14", "#6d6c62", "#93917f"], "ribs", veins="#27d9e6", vein_density=0.02)
CLOAK = Mat(["#040509", "#090a10", "#0f1119", "#161924"], "bands")
SCULK = Mat(["#052229", "#09343e", "#0d4a57", "#177684"], veins="#4ff3ff", vein_density=0.1)
QUARTZ = Mat(["#8f8a82", "#b4afa6", "#cfcac2", "#ece8e2"], "plates")
FEATHER = Mat(["#7c8698", "#9aa6b8", "#bcc6d6", "#dfe6f0"], "fur")
STONE = Mat(["#3c3c42", "#55555c", "#6c6c74", "#85858e"], "brick")
BEETLE = Mat(["#100b05", "#1d150a", "#2c2010", "#40301a"], cell=1)
FUR_BLACK = Mat(["#090a08", "#11140f", "#1a1f17", "#252c21"], "fur", veins="#2e5a1e", vein_density=0.05)
LEAVES = Mat(["#10290b", "#1a3f12", "#245418", "#346e22"], cell=1, mix=(0.25, 0.45, 0.22, 0.08))
VINE = Mat(["#13230b", "#1e3612", "#294a19", "#386224"], "bark")
EMBER = Mat(["#1e0905", "#331009", "#4a170c", "#621f10"], veins="#ff9a2a", vein_density=0.14)
MAGMA = Mat(["#8a2a08", "#d4560f", "#ff8a1f", "#ffd25a"], cell=1, mix=(0.2, 0.45, 0.25, 0.1))
SHADOW = Mat(["#060409", "#0d0912", "#15101c", "#1f1829"], "bands", veins="#9a5cff", vein_density=0.05)
WING_DARK = Mat(["#140604", "#1f0a06", "#2b0f09", "#38140c"])


def maw(eye, brow="#000000", mouth="#050505", teeth="#d8d2bc", eye_w=3, eye_h=2, eye_row=0.3, gap=None,
        mouth_row=0.66, mouth_w=None, mouth_h=3, eyes=2, mask=0.35):
    return face(eye, eyes=eyes, eye_w=eye_w, eye_h=eye_h, eye_row=eye_row, gap=gap, brow=brow, mask=mask,
                mouth=mouth, mouth_row=mouth_row, mouth_w=mouth_w, mouth_h=mouth_h, teeth=teeth)


def jaw(dark, teeth, rows=2):
    """The top of a lower jaw: a dark gap with teeth sticking up and two fangs at the corners."""
    def draw(img):
        h, w = img.shape[:2]
        img[0:rows, 1:w - 1] = C(dark)
        img[rows - 1, 2:w - 2:2] = C(teeth)
        img[0:rows, 1] = C(teeth)
        img[0:rows, w - 2] = C(teeth)
        return img
    return draw


def snout(dark, teeth):
    """Nostrils on top and a toothy mouth along the bottom of a snout."""
    def draw(img):
        h, w = img.shape[:2]
        img[1, w // 2 - 2] = C(dark)
        img[1, w // 2 + 1] = C(dark)
        y = h - 3
        img[y:y + 2, 0:w] = C(dark)
        img[y, 0:w:2] = C(teeth)
        img[y + 1, 1:w:2] = C(teeth)
        return img
    return draw


def claws(m, x, y, z, mat, n=3, length=4, spread=2.0):
    """n claws hanging from a hand (x = centre of the right hand; mirrored to the left), curling forward."""
    for i in range(n):
        cx = x - (n - 1) * spread / 2 + i * spread
        m.pair((cx - 0.5, y - length, z), (1, length, 1), mat, rot=("x", -22.5, (cx, y, z)))


def humanoid(m, leg, body, arm, head, legm, bodym, armm, headm, head_face, gap=0.0, arm_gap=0.0, head_fwd=2.0,
             head_drop=2.0):
    """Legs, body, long arms and a low, forward head. Returns useful positions."""
    lw, lh, ld = leg
    m.pair((-(gap / 2 + lw), 0, -ld / 2), leg, legm)
    bw, bh, bd = body
    m.box((-bw / 2, lh, -bd / 2), body, bodym)
    aw, ah, ad = arm
    top = lh + bh
    m.pair((-(bw / 2 + arm_gap + aw), top - ah, -ad / 2), arm, armm)
    hw, hh, hd = head
    m.box((-hw / 2, top - head_drop, -hd / 2 + head_fwd), head, headm, decal={"south": head_face})
    return {"top": top, "hand_x": -(bw / 2 + arm_gap + aw / 2), "hand_y": top - ah}


# ---------------------------------------------------------------------------------------------------------------------
# Bosses


def deepslate_colossus():
    m = Model("deepslate_colossus")
    m.pair((-13, 0, -6), (10, 6, 12), DEEPSLATE)
    m.pair((-12, 6, -5), (8, 11, 10), DEEPSLATE)
    m.box((-12, 16, -7), (24, 7, 13), DEEPSLATE)
    m.box((-15, 22, -8), (30, 10, 16), DEEPSLATE)
    m.box((-18, 31, -10), (36, 11, 18), DEEPSLATE_MOSS)
    m.box((-13, 41, -9), (26, 4, 13), DEEPSLATE_MOSS)
    # Head sunk low in front of the hunched shoulders, under a heavy brow.
    m.box((-8, 24, 7), (16, 14, 10), DEEPSLATE,
          decal={"south": maw("#2ff6ff", eye_w=3, eye_h=2, eye_row=0.22, gap=4, mouth_row=0.6, mouth_w=12, mouth_h=4)})
    m.box((-9, 36, 14), (18, 3, 4), DEEPSLATE)
    m.pair((-10, 30, 8), (2, 8, 7), DEEPSLATE)
    # Arms hanging to the ground, fists with crystal claws.
    m.pair((-30, 30, -7), (12, 12, 13), DEEPSLATE_MOSS)
    m.pair((-28, 18, -5), (9, 13, 9), DEEPSLATE)
    m.pair((-29, 7, -6), (11, 12, 11), DEEPSLATE)
    m.pair((-30, 2, -7), (13, 7, 13), DEEPSLATE)
    claws(m, -23.5, 4, 5, AMETHYST, n=3, length=5, spread=4)
    # Amethyst bursting out of the back and shoulders.
    m.box((-9, 44, -7), (3, 10, 3), AMETHYST, rot=("x", -22.5))
    m.box((-2, 44, -5), (4, 14, 4), AMETHYST, rot=("z", 22.5))
    m.box((6, 44, -8), (3, 9, 3), AMETHYST, rot=("x", -45))
    m.box((-5, 42, -11), (3, 8, 3), AMETHYST, rot=("x", -45))
    m.pair((-27, 41, -4), (3, 8, 3), AMETHYST, rot=("z", 22.5))
    m.pair((-22, 41, -7), (2, 5, 2), AMETHYST, rot=("x", -22.5))
    m.build()


def drowned_warden():
    m = Model("drowned_warden")
    m.pair((-9, 0, -4), (7, 16, 8), CLOTH_DROWNED)
    m.pair((-9.5, 0, -4.5), (8, 4, 9), SKIN_DROWNED)
    m.box((-11, 15, -6), (22, 5, 12), PRISMARINE)
    m.box((-12, 20, -7), (24, 12, 13), DROWNED_RIBS)
    m.box((-13, 30, -8), (26, 8, 14), PRISMARINE)
    m.pair((-21, 31, -8), (10, 8, 15), PRISMARINE)
    m.pair((-19, 39, -3), (2, 6, 2), PRISMARINE, rot=("z", 22.5))
    # Long arms, webbed claws.
    m.pair((-19, 12, -4), (6, 21, 7), SKIN_DROWNED)
    m.pair((-19.5, 6, -4.5), (7, 7, 8), SKIN_DROWNED)
    claws(m, -16, 7, 2, BONE, n=3, length=5, spread=2)
    # Head low between the shoulders, a crooked crown, kelp hair.
    m.box((-7, 29, 2), (14, 13, 12), SKIN_DROWNED,
          decal={"south": maw("#5fffe0", brow="#06110e", mouth="#020807", teeth="#a8c8b4", eye_row=0.26, gap=4,
                              mouth_row=0.6, mouth_w=10, mouth_h=4)})
    crown = ("z", 22.5, (0, 42, 8))
    m.box((-7.5, 42, 1.5), (15, 2, 13), GOLD, rot=crown)
    for x, z, h in ((-7.5, 1.5, 4), (5.5, 1.5, 4), (-1, 12.5, 6), (-4.5, 12.5, 4), (2.5, 12.5, 4)):
        m.box((x, 44, z), (2, h, 2), GOLD_RAW, rot=crown)
    m.pair((-8, 26, 4), (1, 15, 2), KELP)
    m.pair((-8, 28, 9), (1, 12, 2), KELP)
    m.box((-6, 30, 1), (2, 14, 1), KELP)
    m.box((3, 26, 1), (2, 16, 1), KELP)
    m.pair((-15, 18, -9), (2, 16, 1), KELP)
    # Trident.
    m.box((-24, 0, -1), (2, 50, 2), PRISMARINE)
    m.box((-28, 46, -1), (10, 2, 2), PRISMARINE)
    m.box((-28, 48, -1), (2, 6, 2), PRISMARINE)
    m.box((-20, 48, -1), (2, 6, 2), PRISMARINE)
    m.box((-24, 48, -1), (2, 10, 2), PRISMARINE)
    m.build()


def storm_phantom():
    m = Model("storm_phantom")
    m.box((-7, 0, -12), (14, 8, 25), STORM_BODY)
    m.box((-5, 7, -10), (10, 2, 18), STORM_BODY)
    for z in (5, -1, -7):
        m.box((-1, 8.5, z), (2, 4, 2), BONE_DARK, rot=("x", -22.5))
    # Head with an open jaw, teeth top and bottom, horns swept back.
    m.box((-6, 2, 13), (12, 8, 10), STORM_BODY,
          decal={"south": face("#d8ff4a", eye_w=3, eye_h=2, eye_row=0.2, gap=4, brow="#03050a", mouth="#03050a",
                               mouth_row=0.7, mouth_w=10, mouth_h=3, teeth="#e0e6ee")})
    m.box((-5, -1, 13), (10, 3, 9), STORM_BODY, rot=("x", 22.5, (0, 1, 13)),
          decal={"south": jaw("#03050a", "#e0e6ee"), "up": stripes("#e0e6ee", every=2)})
    m.pair((-6, 9, 13), (2, 2, 10), BONE, rot=("x", 22.5, (-5, 10, 21)))
    m.pair((-6, 11, 4), (2, 2, 7), BONE, rot=("x", 45, (-5, 11, 11)))
    # Wings: inner part up, outer part down; long finger bones and a ragged back edge.
    m.pair((-25, 4, -8), (18, 1, 18), STORM_WING, rot=("z", -22.5, (-7, 4.5, 0)))
    m.pair((-25, 4, 9), (18, 2, 2), BONE_DARK, rot=("z", -22.5, (-7, 4.5, 0)))
    outer = ("z", 22.5, (-23.6, 11.9, 0))
    m.pair((-41, 11.4, -5), (18, 1, 15), STORM_WING, rot=outer)
    m.pair((-35, 11.4, -10), (12, 1, 5), STORM_WING, rot=outer)
    m.pair((-41, 11.4, 8), (18, 2, 2), BONE_DARK, rot=outer)
    m.pair((-45, 11.4, 9), (4, 2, 1), BONE, rot=outer)
    # Tail with a barbed end.
    m.box((-4, 1, -21), (8, 6, 9), STORM_BODY)
    m.box((-2.5, 2, -31), (5, 4, 10), STORM_BODY)
    m.box((-1.5, 2.5, -38), (3, 3, 7), STORM_BODY)
    m.pair((-6, 3, -40), (5, 1, 5), STORM_WING)
    m.box((-0.5, 3, -42), (1, 2, 4), BONE)
    m.build()


def forgemaster():
    m = Model("forgemaster")
    m.pair((-11, 0, -5), (8, 16, 10), BLACKSTONE)
    m.pair((-12, 0, -6), (10, 5, 12), IRON_DARK)
    m.box((-12, 14, -6), (24, 7, 12), CHAIN)
    m.box((-13, 20, -7), (26, 14, 14), BLACKSTONE, decal={"south": spot("#ffd25a", 0.5, 0.4, 4, 3, ring="#ff5a10")})
    m.box((-16, 31, -9), (32, 9, 17), BLACKSTONE)
    m.pair((-25, 31, -9), (11, 9, 17), IRON_DARK)
    for z in (-6, 0):
        m.pair((-23, 40, z), (2, 6, 2), BONE_DARK, rot=("z", 22.5))
    for x, h in ((-5, 4), (-1, 6), (3, 4)):
        m.box((x, 40, -8.5), (2, h, 2), BONE_DARK, rot=("x", -22.5))
    # Long arms with gauntlets and claws.
    m.pair((-23, 14, -5), (8, 18, 10), BLACKSTONE)
    m.pair((-23.5, 6, -5.5), (9, 10, 11), IRON_DARK)
    m.pair((-23, 2, -5), (8, 5, 10), BLACKSTONE)
    claws(m, -19, 3, 3.5, BONE_DARK, n=3, length=4, spread=2.5)
    # Demon head sunk between the shoulders, burning maw, huge curling horns.
    m.box((-7, 27, 4), (14, 13, 12), BLACKSTONE,
          decal={"south": maw("#ffb52a", brow="#050305", mouth="#ff5a10", teeth="#1c1416", eye_row=0.26, gap=4,
                              mouth_row=0.6, mouth_w=10, mouth_h=4)})
    m.pair((-12, 35, 8), (5, 3, 4), BONE_DARK)
    m.pair((-15, 36, 8), (3, 8, 3), BONE, rot=("z", 22.5, (-13.5, 36, 9.5)))
    m.pair((-16, 42, 8.5), (2, 7, 2), BONE, rot=("z", -22.5, (-15, 42, 9.5)))
    m.pair((-13, 47, 8.5), (2, 4, 2), BONE, rot=("z", -45, (-12, 47, 9.5)))
    # Forge hammer.
    m.box((23, 2, -1), (2, 34, 2), WOOD)
    m.box((19, 30, -6), (10, 10, 12), IRON_DARK, decal={"south": spot("#ff8a1f", 0.5, 0.5, 4, 2, ring="#ff5a10"),
                                                          "north": spot("#ff8a1f", 0.5, 0.5, 4, 2, ring="#ff5a10")})
    m.build()


def sand_colossus():
    m = Model("sand_colossus")
    stripe = lambda *sides: {s: stripes("#18307e", every=3, vertical=False) for s in sides}  # noqa: E731
    m.pair((-9, 0, -4), (6, 22, 7), WRAPS)
    m.pair((-10, 0, -5), (8, 3, 9), WRAPS_DARK)
    m.box((-10, 21, -5), (20, 6, 10), WRAPS)
    m.box((-5, 10, 5), (10, 14, 1), WRAPS_DARK)
    m.box((-10.5, 25, -5.5), (21, 2, 11), GOLD)
    m.box((-12, 27, -6), (24, 15, 12), MUMMY_RIBS)
    m.box((-14, 37, -8), (28, 8, 15), WRAPS)
    m.box((-14.5, 41, -8.5), (29, 3, 16), GOLD, decal={"south": stripes("#18307e", every=3)})
    # Long gaunt arms with bony claws and loose wraps.
    m.pair((-19, 14, -3), (5, 28, 6), WRAPS)
    m.pair((-19.5, 8, -3.5), (6, 7, 7), WRAPS_DARK)
    claws(m, -16.5, 9, 2, BONE, n=3, length=6, spread=2)
    m.pair((-18, 2, 0), (1, 12, 2), WRAPS_DARK)
    m.pair((-15, 20, -4), (1, 14, 1), WRAPS_DARK)
    m.box((-4, 8, -6), (2, 18, 1), WRAPS_DARK)
    m.box((3, 4, -6), (1, 22, 1), WRAPS_DARK)
    # Dark face with burning eyes inside a gold headdress, cobra on the brow.
    m.box((-7, 37, 3), (14, 13, 10), WRAPS_DARK,
          decal={"south": maw("#ffd02a", mouth="#000000", teeth="#d8c8a0", eye_row=0.28, gap=4, mouth_row=0.62,
                              mouth_w=8, mouth_h=4, mask=0.25)})
    m.box((-9, 38, -3), (18, 14, 8), GOLD_RAW, decal=stripe("east", "west", "north", "south"))
    m.box((-8.5, 50, -2.5), (17, 2, 15), GOLD_RAW, decal={"up": stripes("#18307e", every=3)})
    m.pair((-10, 28, 3), (3, 18, 4), GOLD_RAW, decal=stripe("south", "east", "west"))
    m.box((-1, 50, 11), (2, 6, 2), GOLD_RAW, decal={"south": spot("#ff2a1a", 0.5, 0.25)})
    m.box((-1.5, 31, 10), (3, 7, 2), LAPIS)
    # Staff topped with a skull.
    m.box((-23, 0, -1), (2, 56, 2), WOOD)
    m.box((-25.5, 56, -2.5), (7, 7, 6), BONE, decal={"south": face("#ffd02a", eye_w=2, eye_h=2, eye_row=0.25, gap=1,
                                                                    mouth="#1a140c", mouth_row=0.7, mouth_w=4,
                                                                    mouth_h=2, teeth="#ebe8d6")})
    m.build()


def frost_titan():
    m = Model("frost_titan")
    m.pair((-12, 0, -6), (9, 20, 11), FROST_SKIN)
    m.pair((-13, 0, -7), (11, 7, 13), ICE)
    m.box((-13, 18, -7), (26, 6, 14), FROST_FUR)
    m.box((-14, 23, -7), (28, 14, 14), ICE, decal={"south": spot("#e6ffff", 0.5, 0.45, 4, 3, ring="#2c548e")})
    m.box((-17, 34, -9), (34, 9, 17), FROST_FUR)
    m.pair((-26, 33, -9), (11, 10, 17), ICE)
    # A forest of ice spikes on the back and shoulders.
    for x, z, h, a in ((-25, -4, 10, ("z", 22.5)), (-21, -7, 7, ("x", -22.5)), (-20, 2, 6, ("z", 45)),
                       (-12, -6, 9, ("x", -22.5)), (-6, -8, 12, ("x", -22.5)), (-2, -3, 8, ("z", 22.5))):
        m.pair((x, 42, z), (3, h, 3), ICE_RAW, rot=a)
    m.box((-1.5, 42, -9), (3, 14, 3), ICE_RAW, rot=("x", -22.5))
    # Arms with ice gauntlets and icicle claws.
    m.pair((-24, 15, -5), (9, 20, 10), FROST_SKIN)
    m.pair((-24.5, 7, -5.5), (10, 9, 11), ICE)
    m.pair((-24, 2, -5), (9, 6, 10), FROST_SKIN)
    claws(m, -19.5, 3, 3.5, ICE_RAW, n=3, length=5, spread=3)
    # Head low and forward, icicle teeth, frozen beard, crown of ice horns.
    m.box((-8, 29, 4), (16, 14, 12), FROST_SKIN,
          decal={"south": maw("#d6ffff", brow="#05101c", mouth="#020810", teeth="#e2f2ff", eye_row=0.26, gap=4,
                              mouth_row=0.6, mouth_w=12, mouth_h=4)})
    m.box((-6, 24, 13), (12, 5, 3), ICE_RAW)
    for x, h in ((-5, 5), (-2, 8), (1, 7), (4, 4)):
        m.box((x, 24 - h, 13.5), (2, h, 2), ICE_RAW)
    m.pair((-10, 39, 8), (3, 10, 3), ICE_RAW, rot=("z", 22.5))
    m.pair((-5, 42, 6), (2, 8, 2), ICE_RAW, rot=("z", 22.5))
    m.box((-1, 42, 6), (2, 10, 2), ICE_RAW)
    # Glacier axe.
    m.box((24, 0, -1), (2, 46, 2), WOOD)
    m.box((23.5, 33, 1), (3, 12, 10), ICE_RAW)
    m.box((23.5, 35, -5), (3, 8, 6), ICE_RAW)
    m.build()


def thornback_beast():
    m = Model("thornback_beast")
    m.box((-11, 12, -18), (22, 18, 32), BARK_FUR)
    m.box((-12, 16, 6), (24, 18, 12), BARK_FUR)
    m.box((-9, 29, -12), (18, 5, 20), BARK_FUR)
    for z, h in ((8, 16), (-16, 14)):
        m.pair((-12, 0, z), (7, h, 8), BARK_FUR_PLAIN)
        m.pair((-12.5, 0, z - 0.5), (8, 3, 9), HOOF)
        claws(m, -8.5, 1, z + 8, BONE, n=3, length=2, spread=2)
    # Head hanging low in front: burning eyes, a long snout full of teeth, huge tusks.
    m.box((-8, 8, 17), (16, 14, 11), BARK_FUR,
          decal={"south": face("#ff2a12", eye_w=3, eye_h=2, eye_row=0.2, gap=6, brow="#000000")})
    m.box((-6, 6, 28), (12, 9, 8), BARK_FUR_PLAIN, decal={"south": snout("#080503", "#e8e0c8")})
    m.box((-5, 3, 28), (10, 3, 7), BARK_FUR_PLAIN, rot=("x", 22.5, (0, 5, 28)), decal={"south": jaw("#080503", "#e8e0c8")})
    m.pair((-8, 9, 33), (2, 9, 2), BONE, rot=("x", 22.5, (-7, 9, 34)))
    m.pair((-8, 17.3, 36.4), (2, 4, 2), BONE, rot=("x", -22.5, (-7, 17.3, 37.4)))
    m.pair((-9, 21, 20), (2, 7, 2), BONE_DARK, rot=("z", 22.5))
    # Thorns all along the back.
    for i, z in enumerate((12, 7, 2, -3, -8, -13)):
        m.box((-1.5, 33, z), (3, 12 - i % 2 * 4, 3), THORN, rot=("x", -22.5))
        m.pair((-9, 31, z + 1), (2, 7 - i % 2 * 2, 2), THORN, rot=("z", 22.5))
        m.pair((-12.5, 24, z), (2, 5, 2), THORN, rot=("z", 45))
    m.box((-2, 20, -27), (4, 4, 10), BARK_FUR_PLAIN, rot=("x", 22.5))
    m.box((-1, 22, -32), (2, 6, 2), THORN, rot=("x", -45))
    m.build()


def hollow_watcher():
    m = Model("hollow_watcher")
    m.box((-12, 14, -8), (24, 32, 1), CLOAK)
    m.pair((-13, 8, -8), (4, 20, 1), CLOAK)
    m.pair((-7, 0, -3), (4, 26, 5), VOID)
    m.pair((-8, 0, -4), (6, 3, 8), VOID)
    claws(m, -5, 2, 4, BONE_DARK, n=2, length=3, spread=3)
    m.box((-8, 25, -4), (16, 5, 8), BONE_DARK)
    m.box((-10, 30, -5), (20, 15, 10), VOID_RIBS)
    m.box((-15, 44, -6), (30, 5, 12), VOID)
    for x, h in ((-15, 10), (-11, 7)):
        m.pair((x, 48, -3), (2, h, 2), SCULK, rot=("z", 22.5))
    # Arms reaching the ground with long bone claws.
    m.pair((-16, 10, -2), (4, 35, 4), VOID)
    m.pair((-17, 5, -3), (6, 6, 6), VOID)
    claws(m, -14, 6, 2, BONE, n=3, length=8, spread=2)
    m.box((-2, 49, -2), (4, 3, 4), VOID)
    # One huge eye and a ring of small ones, a stitched grin, sculk horns.
    m.box((-7, 51, -4), (14, 14, 12), VOID,
          decal={"south": chain(face("#3ff5ff", eyes=1, eye_w=4, eye_h=4, eye_row=0.25, brow="#000000", mask=0.2,
                                     mouth="#000000", mouth_row=0.72, mouth_w=10, mouth_h=2, teeth="#8fdde6"),
                                spot("#3ff5ff", 0.15, 0.25), spot("#3ff5ff", 0.85, 0.25), spot("#3ff5ff", 0.12, 0.5),
                                spot("#3ff5ff", 0.88, 0.5), spot("#3ff5ff", 0.3, 0.1), spot("#3ff5ff", 0.7, 0.1))})
    m.pair((-9, 61, 0), (2, 11, 2), SCULK, rot=("z", 22.5))
    m.pair((-5, 64, -1), (2, 7, 2), SCULK, rot=("z", 22.5))
    m.box((-1, 64, -2), (2, 10, 2), SCULK)
    m.build()


# ---------------------------------------------------------------------------------------------------------------------
# Guards


def guard_face(eye, mouth="#050505", teeth="#d6d0bc", brow="#000000"):
    return face(eye, eye_w=2, eye_h=2, eye_row=0.3, gap=2, brow=brow, mask=0.4, mouth=mouth, mouth_row=0.7,
                mouth_w=6, mouth_h=2, teeth=teeth)


def vault_drowned():
    m = Model("vault_drowned")
    p = humanoid(m, (4, 12, 4), (8, 12, 5), (3, 15, 3), (8, 8, 8), CLOTH_DROWNED, DROWNED_RIBS, SKIN_DROWNED,
                 SKIN_DROWNED, guard_face("#5fffe0", teeth="#a8c8b4"))
    claws(m, p["hand_x"], p["hand_y"], 0.5, BONE, n=2, length=3, spread=1.5)
    m.pair((-6, 21, -3), (3, 4, 6), PRISMARINE)
    m.pair((-4.5, 18, 0), (1, 12, 1), KELP)
    m.box((-1, 20, -5), (2, 10, 1), KELP)
    m.box((-10, 0, 1), (1, 32, 1), PRISMARINE)
    m.box((-11, 29, 1), (3, 1, 1), PRISMARINE)
    m.box((-11, 30, 1), (1, 3, 1), PRISMARINE)
    m.box((-9, 30, 1), (1, 3, 1), PRISMARINE)
    m.box((-10, 30, 1), (1, 5, 1), PRISMARINE)
    m.build()


def visor(eye):
    """A closed helmet: a dark T-shaped slit with two glowing eyes."""
    def draw(img):
        h, w = img.shape[:2]
        y = int(h * 0.38)
        img[y:y + 2, 1:w - 1] = C("#050608")
        img[y:h - 1, w // 2 - 1:w // 2 + 1] = C("#050608")
        img[y, 1:3] = C(eye)
        img[y, w - 3:w - 1] = C(eye)
        img[y + 1, 2] = C(eye)
        img[y + 1, w - 3] = C(eye)
        return img
    return draw


def sky_sentry():
    m = Model("sky_sentry")
    humanoid(m, (4, 12, 4), (8, 12, 4), (4, 13, 4), (8, 8, 8), IRON_DARK, QUARTZ, IRON_DARK, QUARTZ, visor("#7ff8ff"),
             head_fwd=1, head_drop=1)
    m.box((-1, 30, -4), (2, 5, 9), GOLD, rot=("x", -22.5))
    m.pair((-7, 21, -3), (3, 4, 6), QUARTZ)
    m.pair((-7, 24, -2), (1, 4, 1), GOLD_RAW, rot=("z", 22.5))
    m.box((-4.5, 12, -2.5), (9, 2, 5), GOLD)
    m.pair((-14, 8, -3), (8, 16, 1), FEATHER, rot=("z", -22.5, (-2, 22, -3)))
    m.pair((-17, 4, -3), (4, 12, 1), FEATHER, rot=("z", -45, (-11, 14, -3)))
    m.box((7, 0, 1), (1, 34, 1), WOOD)
    m.box((6.5, 34, 0.5), (2, 7, 2), Mat(["#5fb8d8", "#8fdcf2", "#c8f4ff", "#ffffff"], cell=1))
    m.build()


def forge_brute():
    m = Model("forge_brute")
    p = humanoid(m, (4, 10, 5), (11, 11, 7), (5, 14, 5), (9, 8, 8), BLACKSTONE, CHAIN, BLACKSTONE, BLACKSTONE,
                 guard_face("#ffb52a", mouth="#ff5a10", teeth="#1a1416"), arm_gap=0.5, head_fwd=3, head_drop=3)
    m.box((-6.5, 17, -4.5), (13, 5, 8), BLACKSTONE)
    m.pair((-9, 20, -1), (2, 4, 2), BONE_DARK, rot=("z", 22.5))
    m.pair((-6, 22, 2), (2, 4, 2), BONE, rot=("z", 22.5))
    m.pair((-4, 16, 6.5), (1, 3, 1), BONE)
    claws(m, p["hand_x"], p["hand_y"], 1.5, BONE_DARK, n=2, length=2, spread=2)
    m.box((9, 0, -1), (2, 20, 2), WOOD)
    m.box((8, 16, -1), (4, 8, 8), IRON_DARK, decal={"east": spot("#ff8a1f", 0.5, 0.5, 2, 2)})
    m.build()


def tomb_husk():
    m = Model("tomb_husk")
    p = humanoid(m, (4, 12, 4), (8, 12, 4), (3, 15, 3), (8, 8, 8), WRAPS, MUMMY_RIBS, WRAPS, WRAPS_DARK,
                 face("#ffd02a", eye_w=2, eye_h=2, eye_row=0.3, gap=2, brow="#000000", mask=0.3, mouth="#000000",
                      mouth_row=0.7, mouth_w=6, mouth_h=2, teeth="#d8c8a0"))
    claws(m, p["hand_x"], p["hand_y"], 0.5, BONE, n=2, length=3, spread=1.5)
    m.pair((-5.5, 2, 1), (1, 11, 1), WRAPS_DARK)
    m.box((-1, 14, 2.5), (2, 9, 1), WRAPS_DARK)
    m.box((-5, 22, -2), (10, 9, 7), GOLD_RAW, decal={s: stripes("#18307e", every=3, vertical=False)
                                                     for s in ("east", "west", "north", "up")})
    m.build()


def ice_stray():
    m = Model("ice_stray")
    bone = Mat(["#7890a4", "#9cb4c6", "#bed2e0", "#e2eef8"], cell=1)
    m.pair((-3, 0, -1), (2, 12, 2), bone)
    m.box((-4, 12, -2), (8, 12, 4), Mat(["#7890a4", "#9cb4c6", "#121c26", "#1c2a38"], "ribs"))
    m.pair((-6, 9, -1), (2, 15, 2), bone)
    claws(m, -5, 9, 0.5, ICE_RAW, n=2, length=3, spread=1)
    m.box((-4, 22, -2), (8, 8, 8), bone,
          decal={"south": face("#a8faff", eye_w=2, eye_h=2, eye_row=0.3, gap=2, brow="#121c26", mask=0.25,
                               mouth="#0c141c", mouth_row=0.72, mouth_w=6, mouth_h=2, teeth="#e2eef8")})
    m.box((-5, 20, -4), (10, 6, 6), Mat(["#0e1620", "#16222e", "#1e2e3e", "#283c50"], "fur"))
    m.pair((-7, 22, -2), (3, 5, 4), ICE_RAW, rot=("z", 22.5))
    for x, h in ((-3, 4), (-1, 6), (1, 5)):
        m.box((x, 30, 0), (2, h, 2), ICE_RAW)
    m.box((6, 8, 1), (1, 18, 1), WOOD)
    m.box((6, 7, 1), (1, 1, 2), WOOD)
    m.box((6, 26, 1), (1, 1, 2), WOOD)
    m.build()


def stone_crawler():
    m = Model("stone_crawler")
    m.box((-3.5, 0, 6), (7, 4, 4), STONE, decal={"south": face("#ff2a1a", eye_w=1, eye_h=1, eye_row=0.2, gap=3,
                                                                 mouth="#050505", mouth_row=0.6, mouth_w=5,
                                                                 mouth_h=2, teeth="#d8d2bc")})
    m.pair((-4, 0, 9), (1, 1, 3), BONE, rot=("y", -22.5, (-3.5, 0.5, 9)))
    m.box((-4, 0, 1), (8, 5, 5), STONE)
    m.box((-3.5, 0, -4), (7, 4.5, 5), STONE)
    m.box((-2.5, 0, -8), (5, 3.5, 4), STONE)
    m.box((-1.5, 0, -11), (3, 2.5, 3), STONE)
    for z in (2, -2, -6):
        m.pair((-7, 0, z), (3, 1, 1), STONE, rot=("z", 22.5, (-4, 1, z)))
    for x, z, h in ((-2, 2, 3), (1, -2, 3), (-1, -6, 2)):
        m.box((x, 4, z), (1, h, 1), AMETHYST, rot=("x", -22.5))
    m.build()


def scarab():
    m = Model("scarab")
    shell = Mat(["#4a320a", "#7a5410", "#a8781a", "#e0b040"], cell=1)
    m.box((-5, 1, -6), (10, 5, 12), shell)
    m.box((-0.5, 5.9, -6), (1, 0.2, 12), BEETLE)
    m.box((-3, 1, 6), (6, 4, 3), BEETLE, decal={"south": face("#7dff6a", eye_w=1, eye_h=1, eye_row=0.2, gap=2,
                                                                mask=None, brow=None)})
    m.pair((-4, 1.5, 9), (1, 1, 4), BEETLE, rot=("y", -22.5, (-3, 2, 9)))
    m.box((-0.5, 4, 7), (1, 4, 1), BEETLE, rot=("x", 22.5))
    for z in (3, -1, -5):
        m.pair((-8, 0, z), (3, 1, 1), BEETLE, rot=("z", 22.5, (-5, 1, z)))
    m.build()


def sculk_lurker():
    m = Model("sculk_lurker")
    leg = Mat(["#020b0d", "#051418", "#082028", "#0d3038"])
    m.box((-4, 3, 0), (8, 5, 7), SCULK, decal={"south": chain(
        face("#4ff3ff", eye_w=1, eye_h=1, eye_row=0.2, gap=2, mask=None, brow=None, mouth="#000000", mouth_row=0.55,
             mouth_w=6, mouth_h=2, teeth="#cfe8ea"), spot("#4ff3ff", 0.15, 0.1), spot("#4ff3ff", 0.85, 0.1))})
    m.pair((-3, 2, 7), (1, 2, 1), BONE)
    m.box((-5, 3, -10), (10, 7, 10), SCULK)
    for z in (5, 2, -2, -6):
        m.pair((-14, 6, z), (10, 1, 1), leg, rot=("z", 22.5, (-4, 6.5, z)))
        m.pair((-14.5, 0, z), (1, 9, 1), leg)
    m.build()


def jungle_stalker():
    m = Model("jungle_stalker")
    m.box((-4, 6, -10), (8, 7, 20), FUR_BLACK)
    for z in (6, -9):
        m.pair((-4, 0, z), (3, 7, 3), FUR_BLACK)
        claws(m, -2.5, 1, z + 3, BONE, n=2, length=1, spread=1)
    m.box((-4, 5, 9), (8, 7, 6), FUR_BLACK, decal={"south": face("#a8ff3a", eye_w=2, eye_h=1, eye_row=0.3, gap=2,
                                                                   brow="#000000")})
    m.box((-2.5, 5, 15), (5, 4, 3), FUR_BLACK, decal={"south": snout("#000000", "#e8e8d8")})
    m.pair((-4, 12, 10), (2, 3, 1), FUR_BLACK, rot=("z", 22.5))
    m.pair((-2, 3, 17), (1, 3, 1), BONE)
    m.box((-1, 10, -19), (2, 2, 10), FUR_BLACK, rot=("x", 22.5))
    m.box((-4.5, 12.5, -6), (9, 1, 10), LEAVES)
    m.build()


def vine_creeper():
    m = Model("vine_creeper")
    m.box((-5, 2, -6), (10, 6, 12), LEAVES)
    m.box((-3, 1, 5), (6, 6, 4), VINE, decal={"south": face("#ff3ad0", eyes=1, eye_w=2, eye_h=2, eye_row=0.15,
                                                              mask=0.4, mouth="#000000", mouth_row=0.6, mouth_w=6,
                                                              mouth_h=2, teeth="#d8f0c0")})
    for z in (3, -2, -6):
        m.pair((-11, 0, z), (6, 2, 1), VINE, rot=("z", 22.5, (-5, 3, z)))
        m.pair((-6, 6, z), (2, 4, 1), THORN, rot=("z", 22.5))
    m.box((-1, 8, -2), (2, 4, 2), VINE)
    m.box((-2, 12, -3), (4, 2, 4), Mat(["#6a0e3a", "#a01a58", "#d42a7e", "#ff6ab0"], cell=1))
    m.build()


def gale_phantom():
    m = Model("gale_phantom")
    m.box((-3, 0, -6), (6, 4, 12), STORM_BODY)
    m.box((-3, 0, 6), (6, 4, 5), STORM_BODY, decal={"south": face("#d8ff4a", eye_w=2, eye_h=1, eye_row=0.1, gap=1,
                                                                    mask=None, mouth="#000000", mouth_row=0.5,
                                                                    mouth_w=6, mouth_h=2, teeth="#e0e6ee")})
    m.pair((-3, 4, 6), (1, 1, 5), BONE, rot=("x", 22.5, (-2.5, 4.5, 11)))
    m.pair((-13, 2, -4), (10, 1, 9), STORM_WING, rot=("z", -22.5, (-3, 2.5, 0)))
    m.pair((-22, 5.6, -3), (9, 1, 7), STORM_WING, rot=("z", 22.5, (-12.2, 6.3, 0)))
    m.box((-1.5, 0.5, -12), (3, 2, 6), STORM_BODY)
    m.build()


def ember_imp():
    m = Model("ember_imp")
    m.box((-3, 4, -2), (6, 7, 4), EMBER)
    m.pair((-2.5, 0, -1), (2, 4, 2), EMBER)
    m.pair((-5, 4, -1), (2, 7, 2), EMBER)
    claws(m, -4, 4, 0.5, BONE_DARK, n=2, length=2, spread=1)
    m.box((-3.5, 10, -2), (7, 6, 6), EMBER, decal={"south": face("#ffe36b", eye_w=2, eye_h=2, eye_row=0.2, gap=1,
                                                                   brow="#000000", mouth="#ff8a1f", mouth_row=0.6,
                                                                   mouth_w=5, mouth_h=2, teeth="#1e0905")})
    m.pair((-4.5, 15, 0), (1, 5, 1), BONE_DARK, rot=("z", 22.5))
    m.pair((-11, 6, -2), (8, 7, 1), WING_DARK, rot=("z", -22.5, (-3, 10, -2)))
    m.box((-0.5, 3, -7), (1, 1, 5), EMBER)
    m.box((-1, 2.5, -8), (2, 2, 1), MAGMA)
    m.build()


def frostbite_bear():
    m = Model("frostbite_bear")
    fur = Mat(["#7d93a6", "#a2b6c6", "#c4d4e0", "#e4eef4"], "fur")
    m.box((-7, 9, -11), (14, 13, 22), fur)
    m.box((-7.5, 13, 5), (15, 13, 8), fur)
    for z in (5, -10):
        m.pair((-7, 0, z), (5, 10, 5), fur)
        claws(m, -4.5, 1, z + 5, Mat(["#1a1d22", "#2a2e35", "#3a3f48", "#4a505a"]), n=2, length=1, spread=2)
    m.box((-5, 8, 12), (10, 9, 7), fur, decal={"south": face("#7ff6ff", eye_w=2, eye_h=1, eye_row=0.25, gap=4,
                                                               brow="#2a3440")})
    m.box((-3, 7, 19), (6, 6, 4), fur, decal={"south": snout("#14181e", "#ffffff")})
    m.pair((-5, 17, 14), (2, 2, 2), fur)
    for z, h in ((-8, 6), (-4, 8), (0, 9), (4, 7)):
        m.box((-1.5, 23, z), (3, h, 3), ICE_RAW, rot=("x", -22.5))
    m.build()


def shadow_echo():
    m = Model("shadow_echo")
    m.box((-4, 16, -3), (8, 8, 8), SHADOW, decal={"south": face("#d39bff", eye_w=2, eye_h=2, eye_row=0.3, gap=2,
                                                                  brow="#000000", mask=0.2, mouth="#000000",
                                                                  mouth_row=0.7, mouth_w=6, mouth_h=2,
                                                                  teeth="#3a2c4a")})
    m.box((-5, 16, -4), (10, 10, 3), SHADOW)
    m.box((-5, 24, -4), (10, 2, 9), SHADOW)
    m.box((-4.5, 8, -2.5), (9, 9, 5), SHADOW)
    m.box((-3.5, 3, -2), (7, 5, 4), SHADOW)
    m.box((-2, 0, -1.5), (4, 3, 3), SHADOW)
    m.pair((-8, 5, -1), (3, 13, 3), SHADOW, rot=("z", 22.5, (-6.5, 17, 0)))
    m.build()


MODELS = {
    "deepslate_colossus": (deepslate_colossus, 4.3), "drowned_warden": (drowned_warden, 3.3),
    "storm_phantom": (storm_phantom, 2.4), "forgemaster": (forgemaster, 3.7), "sand_colossus": (sand_colossus, 4.7),
    "frost_titan": (frost_titan, 4.8), "thornback_beast": (thornback_beast, 3.1), "hollow_watcher": (hollow_watcher, 4.8),
    "vault_drowned": (vault_drowned, 1.95), "sky_sentry": (sky_sentry, 2.0), "forge_brute": (forge_brute, 1.95),
    "tomb_husk": (tomb_husk, 1.95), "ice_stray": (ice_stray, 2.0), "stone_crawler": (stone_crawler, 0.4),
    "scarab": (scarab, 0.4), "sculk_lurker": (sculk_lurker, 0.55), "jungle_stalker": (jungle_stalker, 0.95),
    "vine_creeper": (vine_creeper, 0.55), "gale_phantom": (gale_phantom, 0.6), "ember_imp": (ember_imp, 0.6),
    "frostbite_bear": (frostbite_bear, 1.4), "shadow_echo": (shadow_echo, 0.85),
}
