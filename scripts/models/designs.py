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
SCALES_SEA = Mat(["#0f2a26", "#1a4038", "#25584c", "#367462"], "scales")
FIN = Mat(["#173f3a", "#25605a", "#3a8a80", "#5fb8a8"], "bark")
CHITIN = Mat(["#120d06", "#22190b", "#3a2a12", "#6a5222"], "plates")
JACKAL = Mat(["#07070a", "#0f0f14", "#18181f", "#24242e"])
SKULL = Mat(["#a8a48e", "#c8c4ac", "#dedac4", "#f4f2e4"], cell=1)
WING_DARK = Mat(["#140604", "#1f0a06", "#2b0f09", "#38140c"])
STORM_ARMOR = Mat(["#121a2a", "#1d2a42", "#2a3d5e", "#3d5884"], "plates", veins="#a8f6ff", vein_density=0.04)
STORM_COPPER = Mat(["#24524a", "#337264", "#468f7e", "#64b8a2"], "plates")
STORM_CAPE = Mat(["#060c14", "#0c1622", "#122033", "#1a2c45"], "bands", veins="#2fe6f2", vein_density=0.03)
LIGHTNING = Mat(["#6fd0ff", "#a8f0ff", "#d6fbff", "#ffffff"], cell=1)


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
    m.part("leg", (-(gap / 2 + lw / 2), lh, 0), "leg", mirror=True)
    m.pair((-(gap / 2 + lw), 0, -ld / 2), leg, legm)
    bw, bh, bd = body
    m.part("body", (0, lh, 0), "body")
    m.box((-bw / 2, lh, -bd / 2), body, bodym)
    aw, ah, ad = arm
    top = lh + bh
    m.part("arm", (-(bw / 2 + arm_gap + aw / 2), top - 1, 0), "arm", mirror=True)
    m.pair((-(bw / 2 + arm_gap + aw), top - ah, -ad / 2), arm, armm)
    hw, hh, hd = head
    m.part("head", (0, top - head_drop, head_fwd), "head")
    m.box((-hw / 2, top - head_drop, -hd / 2 + head_fwd), head, headm, decal={"south": head_face})
    return {"top": top, "hand_x": -(bw / 2 + arm_gap + aw / 2), "hand_y": top - ah}


# ---------------------------------------------------------------------------------------------------------------------
# Bosses


def deepslate_colossus():
    m = Model("deepslate_colossus")
    m.part("leg", (-8, 17, 0), "leg", mirror=True)
    m.pair((-13, 0, -6), (10, 6, 12), DEEPSLATE)
    m.pair((-12, 6, -5), (8, 11, 10), DEEPSLATE)
    m.part("body", (0, 17, 0), "body")
    m.box((-12, 16, -7), (24, 7, 13), DEEPSLATE)
    m.box((-15, 22, -8), (30, 10, 16), DEEPSLATE)
    m.box((-18, 31, -10), (36, 11, 18), DEEPSLATE_MOSS)
    m.box((-13, 41, -9), (26, 4, 13), DEEPSLATE_MOSS)
    # Head sunk low in front of the hunched shoulders, under a heavy brow.
    m.part("head", (0, 31, 8), "head")
    m.box((-8, 24, 7), (16, 14, 10), DEEPSLATE,
          decal={"south": maw("#2ff6ff", eye_w=3, eye_h=2, eye_row=0.22, gap=4, mouth_row=0.6, mouth_w=12, mouth_h=4)})
    m.box((-9, 36, 14), (18, 3, 4), DEEPSLATE)
    m.pair((-10, 30, 8), (2, 8, 7), DEEPSLATE)
    # Arms hanging to the ground, fists with crystal claws.
    m.use("body")
    m.pair((-30, 30, -7), (12, 12, 13), DEEPSLATE_MOSS)
    m.part("arm", (-23.5, 31, 0), "arm", mirror=True)
    m.pair((-28, 18, -5), (9, 13, 9), DEEPSLATE)
    m.pair((-29, 7, -6), (11, 12, 11), DEEPSLATE)
    m.pair((-30, 2, -7), (13, 7, 13), DEEPSLATE)
    claws(m, -23.5, 4, 5, AMETHYST, n=3, length=5, spread=4)
    # Amethyst bursting out of the back and shoulders.
    m.use("body")
    m.box((-9, 44, -7), (3, 10, 3), AMETHYST, rot=("x", -22.5))
    m.box((-2, 44, -5), (4, 14, 4), AMETHYST, rot=("z", 22.5))
    m.box((6, 44, -8), (3, 9, 3), AMETHYST, rot=("x", -45))
    m.box((-5, 42, -11), (3, 8, 3), AMETHYST, rot=("x", -45))
    m.pair((-27, 41, -4), (3, 8, 3), AMETHYST, rot=("z", 22.5))
    m.pair((-22, 41, -7), (2, 5, 2), AMETHYST, rot=("x", -22.5))
    return m


def drowned_warden():
    """A sea serpent: a coiled tail on the ground, a drowned king's body, tentacles hanging from the face."""
    m = Model("drowned_warden")
    # Coiled tail.
    m.box((-14, 0, -12), (28, 6, 8), SCALES_SEA)
    m.box((-17, 0, -6), (7, 6, 14), SCALES_SEA)
    m.box((10, 0, -6), (7, 6, 14), SCALES_SEA)
    m.box((-12, 0, 8), (16, 5, 6), SCALES_SEA)
    m.box((4, 0, 9), (10, 4, 4), SCALES_SEA)
    m.box((14, 0, 10), (5, 3, 3), SCALES_SEA)
    m.box((19, 0, 10.5), (3, 2, 2), FIN)
    # Body rising out of the coil.
    m.part("torso", (0, 8, 0), "torso")
    m.box((-8, 5, -6), (16, 8, 12), SCALES_SEA)
    m.box((-7, 12, -4), (14, 9, 11), SCALES_SEA, decal={"south": stripes("#4f8f78", every=2, vertical=False)})
    m.box((-0.5, 8, -9), (1, 26, 4), FIN)
    m.box((-11, 20, -5), (22, 13, 11), DROWNED_RIBS)
    m.pair((-18, 29, -6), (8, 6, 12), PRISMARINE)
    m.pair((-17, 34, -2), (2, 6, 2), PRISMARINE, rot=("z", 22.5))
    m.part("arm", (-14.5, 31, 0), "arm", parent="torso", mirror=True)
    m.pair((-17, 12, -3), (5, 19, 6), SKIN_DROWNED)
    claws(m, -14.5, 12, 1.5, BONE, n=3, length=5, spread=1.5)
    # Head with fin ears, a crooked crown and a beard of tentacles.
    m.part("head", (0, 33, 4), "head", parent="torso")
    m.box((-6, 31, 0), (12, 12, 11), SKIN_DROWNED,
          decal={"south": maw("#5fffe0", brow="#06110e", mouth="#020807", teeth="#a8c8b4", eye_row=0.22, gap=3,
                              mouth_row=0.55, mouth_w=10, mouth_h=3)})
    m.pair((-10, 35, 2), (4, 8, 6), FIN, rot=("z", 22.5))
    crown = ("z", 22.5, (0, 43, 5))
    m.box((-6.5, 43, -0.5), (13, 2, 12), GOLD, rot=crown)
    for x, z, h in ((-6.5, -0.5, 4), (4.5, -0.5, 4), (-1, 9.5, 6), (-4.5, 9.5, 4), (2.5, 9.5, 4)):
        m.box((x, 45, z), (2, h, 2), GOLD_RAW, rot=crown)
    for x, h in ((-5, 8), (-2.5, 11), (0.5, 12), (3, 9)):
        m.box((x, 33 - h, 9), (2, h, 2), SKIN_DROWNED, rot=("x", 22.5, (x + 1, 33, 10)))
    # Trident.
    m.part("trident", (-14.5, 31, 0), "static", parent="arm_r")
    m.box((-22, 0, -1), (2, 50, 2), PRISMARINE)
    m.box((-26, 46, -1), (10, 2, 2), PRISMARINE)
    m.box((-26, 48, -1), (2, 6, 2), PRISMARINE)
    m.box((-18, 48, -1), (2, 6, 2), PRISMARINE)
    m.box((-22, 48, -1), (2, 10, 2), PRISMARINE)
    m.box((-20, 14, -2), (3, 5, 5), SKIN_DROWNED)
    return m


def storm_phantom():
    m = Model("storm_phantom")
    m.part("body", (0, 4, 0), "body")
    m.box((-7, 0, -12), (14, 8, 25), STORM_BODY)
    m.box((-5, 7, -10), (10, 2, 18), STORM_BODY)
    for z in (5, -1, -7):
        m.box((-1, 8.5, z), (2, 4, 2), BONE_DARK, rot=("x", -22.5))
    # Head with an open jaw, teeth top and bottom, horns swept back.
    m.part("head", (0, 5, 13), "head")
    m.box((-6, 2, 13), (12, 8, 10), STORM_BODY,
          decal={"south": face("#d8ff4a", eye_w=3, eye_h=2, eye_row=0.2, gap=4, brow="#03050a", mouth="#03050a",
                               mouth_row=0.7, mouth_w=10, mouth_h=3, teeth="#e0e6ee")})
    m.part("jaw", (0, 1, 13), "jaw", parent="head")
    m.box((-5, -1, 13), (10, 3, 9), STORM_BODY, rot=("x", 22.5, (0, 1, 13)),
          decal={"south": jaw("#03050a", "#e0e6ee"), "up": stripes("#e0e6ee", every=2)})
    m.use("head")
    m.pair((-6, 9, 13), (2, 2, 10), BONE, rot=("x", 22.5, (-5, 10, 21)))
    m.pair((-6, 11, 4), (2, 2, 7), BONE, rot=("x", 45, (-5, 11, 11)))
    # Wings: inner part up, outer part down; long finger bones and a ragged back edge.
    m.part("wing", (-7, 4.5, 0), "wing", mirror=True)
    m.pair((-25, 4, -8), (18, 1, 18), STORM_WING, rot=("z", -22.5, (-7, 4.5, 0)))
    m.pair((-25, 4, 9), (18, 2, 2), BONE_DARK, rot=("z", -22.5, (-7, 4.5, 0)))
    m.part("wingtip", (-23.6, 11.9, 0), "wingtip", parent="wing", mirror=True)
    outer = ("z", 22.5, (-23.6, 11.9, 0))
    m.pair((-41, 11.4, -5), (18, 1, 15), STORM_WING, rot=outer)
    m.pair((-35, 11.4, -10), (12, 1, 5), STORM_WING, rot=outer)
    m.pair((-41, 11.4, 8), (18, 2, 2), BONE_DARK, rot=outer)
    m.pair((-45, 11.4, 9), (4, 2, 1), BONE, rot=outer)
    # Tail with a barbed end.
    m.part("tail", (0, 4, -12), "tail")
    m.box((-4, 1, -21), (8, 6, 9), STORM_BODY)
    m.box((-2.5, 2, -31), (5, 4, 10), STORM_BODY)
    m.box((-1.5, 2.5, -38), (3, 3, 7), STORM_BODY)
    m.pair((-6, 3, -40), (5, 1, 5), STORM_WING)
    m.box((-0.5, 3, -42), (1, 2, 4), BONE)
    return m


def forgemaster():
    """A four-armed forge demon on goat legs, with a furnace for a belly and a barbed tail."""
    m = Model("forgemaster")
    m.part("leg", (-8.5, 26, 0), "leg", mirror=True)
    m.pair((-12, 15, -4), (7, 11, 8), BLACKSTONE, rot=("x", -22.5))
    m.pair((-11.5, 4, 0), (6, 12, 6), BLACKSTONE, rot=("x", 22.5))
    m.pair((-12.5, 0, -3), (7, 5, 8), IRON_DARK)
    m.part("body", (0, 26, 0), "body")
    m.box((-11, 24, -5), (22, 6, 10), CHAIN)
    m.part("tail", (0, 26, -5), "tail")
    m.box((-2, 24, -14), (4, 4, 10), BLACKSTONE, rot=("x", 22.5))
    m.box((-1.5, 20, -22), (3, 3, 9), BLACKSTONE)
    m.box((-2.5, 19, -26), (5, 5, 4), BONE_DARK, rot=("x", 45))
    m.use("body")
    furnace = chain(spot("#ffd25a", 0.5, 0.55, 10, 6, ring="#ff5a10"), stripes("#1d181f", every=3))
    m.box((-13, 29, -7), (26, 13, 14), BLACKSTONE, decal={"south": furnace})
    m.box((-16, 40, -8), (32, 8, 16), BLACKSTONE)
    m.pair((-26, 39, -8), (11, 9, 16), IRON_DARK)
    for x in (-24, -20):
        m.pair((x, 48, -2), (2, 6, 2), BONE_DARK, rot=("z", 22.5))
    for x, h in ((-5, 5), (-1, 8), (3, 5)):
        m.box((x, 48, -8), (2, h, 2), BONE_DARK, rot=("x", -22.5))
    # Big upper arms with gauntlets and claws.
    m.part("arm", (-20, 40, 0), "arm", mirror=True)
    m.pair((-24, 22, -5), (8, 18, 9), BLACKSTONE)
    m.pair((-24.5, 14, -5.5), (9, 9, 10), IRON_DARK)
    claws(m, -20, 14, 3, BONE_DARK, n=3, length=4, spread=2.5)
    # Smaller lower arms reaching forward.
    m.part("arm2", (-14.5, 30, 5.5), "arm2", mirror=True)
    m.pair((-17, 18, 3), (5, 13, 5), BLACKSTONE, rot=("x", -45, (-14.5, 30, 5.5)))
    # Small demon head sunk between the shoulders, ram horns.
    m.part("head", (0, 38, 8), "head")
    m.box((-6, 36, 6), (12, 11, 10), BLACKSTONE,
          decal={"south": maw("#ffb52a", brow="#050305", mouth="#ff5a10", teeth="#1c1416", eye_row=0.24, gap=3,
                              mouth_row=0.58, mouth_w=8, mouth_h=3)})
    m.pair((-10, 43, 8), (5, 4, 5), BONE_DARK)
    m.pair((-13, 40, 8), (4, 7, 5), BONE, rot=("z", -22.5))
    m.pair((-12, 36, 10), (3, 5, 3), BONE, rot=("x", -22.5))
    # Forge hammer.
    m.part("hammer", (20, 40, 0), "static", parent="arm_l")
    m.box((25, 2, -1), (2, 30, 2), WOOD)
    m.box((21, 28, -6), (10, 9, 12), IRON_DARK, decal={"south": spot("#ff8a1f", 0.5, 0.5, 4, 2, ring="#ff5a10")})
    return m


def sand_colossus():
    """A mummified pharaoh grown into a giant scorpion: eight legs, pincers and a stinger raised over its head."""
    m = Model("sand_colossus")
    stripe = lambda *sides: {s: stripes("#18307e", every=3, vertical=False) for s in sides}  # noqa: E731
    m.part("body", (0, 9, 0), "body")
    m.box((-9, 6, -16), (18, 7, 24), CHITIN)
    for z in (4, -2, -8, -14):
        m.box((-8, 12.5, z), (16, 1, 5), GOLD_RAW)
    m.box((-7, 5, -22), (14, 6, 7), CHITIN)
    # Eight legs: up and out from the body, then down to sharp tips.
    for i, z in enumerate((6, 0, -6, -12)):
        m.part(f"leg{i}", (-9, 10, z + 1), "leg", mirror=True, phase=i * 1.6)
        m.pair((-20, 9, z), (11, 2, 2), CHITIN, rot=("z", -22.5, (-9, 10, z + 1)))
        m.pair((-24, 0, z), (2, 14, 2), CHITIN, rot=("z", 22.5, (-23, 14, z + 1)))
    # Pincers held up in front.
    m.part("claw", (-11, 11, 6), "arm2", mirror=True)
    m.pair((-13, 9, 6), (4, 4, 12), CHITIN, rot=("x", -22.5, (-11, 11, 6)))
    m.pair((-17, 13, 16), (9, 8, 9), CHITIN)
    m.pair((-17, 15, 25), (3, 6, 7), CHITIN)
    m.pair((-12, 13, 25), (3, 4, 5), CHITIN)
    # Stinger tail curling high over the back.
    m.part("tail", (0, 10, -25), "tail")
    m.box((-3, 8, -29), (6, 6, 8), CHITIN)
    m.box((-2.5, 12, -33), (5, 10, 5), CHITIN, rot=("x", -22.5))
    m.box((-2.5, 21, -34), (5, 10, 5), CHITIN)
    m.box((-2, 30, -32), (4, 8, 4), CHITIN, rot=("x", 22.5))
    m.box((-2, 36, -27), (4, 4, 6), CHITIN, rot=("x", 45))
    m.box((-1, 34, -21), (2, 2, 5), Mat(["#6a5a10", "#a89020", "#e0c838", "#fff27a"], cell=1), rot=("x", 45))
    # Mummy torso on the front.
    m.part("torso", (0, 14, 3), "torso")
    m.box((-8, 14, -2), (16, 14, 10), MUMMY_RIBS)
    m.box((-10, 26, -3), (20, 6, 12), WRAPS)
    m.box((-10.5, 30, -3.5), (21, 2, 13), GOLD, decal={"south": stripes("#18307e", every=3)})
    m.part("arm", (-12, 28, 2.5), "arm", parent="torso", mirror=True)
    m.pair((-14, 12, 0), (4, 18, 5), WRAPS)
    claws(m, -12, 12, 3, BONE, n=3, length=5, spread=1.5)
    m.pair((-13, 6, 2), (1, 10, 1), WRAPS_DARK)
    m.part("head", (0, 31, 6), "head", parent="torso")
    m.box((-6, 30, 2), (12, 12, 9), WRAPS_DARK,
          decal={"south": maw("#ffd02a", mouth="#000000", teeth="#d8c8a0", eye_row=0.28, gap=3, mouth_row=0.62,
                              mouth_w=8, mouth_h=3, mask=0.25)})
    m.box((-8, 31, -3), (16, 13, 7), GOLD_RAW, decal=stripe("east", "west", "north", "south"))
    m.box((-7.5, 42, -2.5), (15, 2, 14), GOLD_RAW, decal={"up": stripes("#18307e", every=3)})
    m.pair((-9, 22, 2), (3, 15, 4), GOLD_RAW, decal=stripe("south", "east", "west"))
    m.box((-1, 42, 9), (2, 6, 2), GOLD_RAW, decal={"south": spot("#ff2a1a", 0.5, 0.25)})
    return m


def frost_titan():
    """A starved frost giant: an antlered skull for a head, ribs showing, long thin arms with icicle claws."""
    m = Model("frost_titan")
    m.part("leg", (-6, 26, 0), "leg", mirror=True)
    m.pair((-8, 0, -3), (4, 26, 5), FROST_SKIN)
    m.pair((-8.5, 0, -3.5), (5, 4, 7), ICE)
    m.part("body", (0, 26, 0), "body")
    m.box((-7, 25, -4), (14, 4, 8), FROST_SKIN)
    m.box((-10, 28, -2), (20, 13, 9), Mat(["#0c1824", "#1f3448", "#8fa4b6", "#b2c4d2"], "ribs"))
    m.box((-11, 38, 0), (22, 8, 11), FROST_SKIN)
    m.box((-12, 30, -5), (24, 17, 3), FROST_FUR)
    for z, h in ((-4, 8), (0, 10), (4, 7)):
        m.box((-1.5, 45, z), (3, h, 3), ICE_RAW, rot=("x", -22.5))
    m.pair((-12, 44, 0), (3, 7, 3), ICE_RAW, rot=("z", 22.5))
    # Arms nearly dragging on the ground.
    m.part("arm", (-13, 43, 5), "arm", mirror=True)
    m.pair((-15, 10, 3), (4, 34, 4), FROST_SKIN)
    m.pair((-15.5, 6, 2.5), (5, 5, 5), FROST_SKIN)
    claws(m, -13, 7, 6, ICE_RAW, n=3, length=6, spread=1.5)
    # Neck pushed forward, deer skull with a long toothy snout and huge antlers.
    m.part("head", (0, 42, 11), "head")
    m.box((-2, 40, 10), (4, 4, 6), FROST_SKIN)
    m.box((-5, 38, 14), (10, 10, 9), SKULL,
          decal={"south": face("#7ff8ff", eye_w=2, eye_h=2, eye_row=0.3, gap=2, brow="#000000", mask=0.15)})
    m.box((-3, 37, 23), (6, 6, 7), SKULL, decal={"south": snout("#05080c", "#f4f2e4")})
    m.pair((-6, 46, 17), (2, 10, 2), SKULL, rot=("z", 22.5, (-5, 46, 18)))
    m.pair((-15, 53, 17), (8, 2, 2), SKULL, rot=("z", -22.5, (-9, 54, 18)))
    m.pair((-11, 55, 17), (2, 7, 2), SKULL, rot=("z", 22.5))
    m.pair((-16, 56, 17), (2, 9, 2), SKULL, rot=("z", 22.5))
    m.pair((-8, 57, 17), (2, 5, 2), SKULL)
    return m


def thornback_beast():
    m = Model("thornback_beast")
    m.part("body", (0, 20, 0), "body")
    m.box((-11, 12, -18), (22, 18, 32), BARK_FUR)
    m.box((-12, 16, 6), (24, 18, 12), BARK_FUR)
    m.box((-9, 29, -12), (18, 5, 20), BARK_FUR)
    for z, h in ((8, 16), (-16, 14)):
        m.part("legf" if z > 0 else "legb", (-8.5, h, z + 4), "leg_front" if z > 0 else "leg_back", mirror=True)
        m.pair((-12, 0, z), (7, h, 8), BARK_FUR_PLAIN)
        m.pair((-12.5, 0, z - 0.5), (8, 3, 9), HOOF)
        claws(m, -8.5, 1, z + 8, BONE, n=3, length=2, spread=2)
    # Head hanging low in front: burning eyes, a long snout full of teeth, huge tusks.
    m.part("head", (0, 18, 17), "head")
    m.box((-8, 8, 17), (16, 14, 11), BARK_FUR,
          decal={"south": face("#ff2a12", eye_w=3, eye_h=2, eye_row=0.2, gap=6, brow="#000000")})
    m.box((-6, 6, 28), (12, 9, 8), BARK_FUR_PLAIN, decal={"south": snout("#080503", "#e8e0c8")})
    m.part("jaw", (0, 5, 28), "jaw", parent="head")
    m.box((-5, 3, 28), (10, 3, 7), BARK_FUR_PLAIN, rot=("x", 22.5, (0, 5, 28)), decal={"south": jaw("#080503", "#e8e0c8")})
    m.use("head")
    m.pair((-8, 9, 33), (2, 9, 2), BONE, rot=("x", 22.5, (-7, 9, 34)))
    m.pair((-8, 17.3, 36.4), (2, 4, 2), BONE, rot=("x", -22.5, (-7, 17.3, 37.4)))
    m.pair((-9, 21, 20), (2, 7, 2), BONE_DARK, rot=("z", 22.5))
    # Thorns all along the back.
    m.use("body")
    for i, z in enumerate((12, 7, 2, -3, -8, -13)):
        m.box((-1.5, 33, z), (3, 12 - i % 2 * 4, 3), THORN, rot=("x", -22.5))
        m.pair((-9, 31, z + 1), (2, 7 - i % 2 * 2, 2), THORN, rot=("z", 22.5))
        m.pair((-12.5, 24, z), (2, 5, 2), THORN, rot=("z", 45))
    m.part("tail", (0, 22, -18), "tail")
    m.box((-2, 20, -27), (4, 4, 10), BARK_FUR_PLAIN, rot=("x", 22.5))
    m.box((-1, 22, -32), (2, 6, 2), THORN, rot=("x", -45))
    return m


def hollow_watcher():
    """A floating eye: one giant eye over a jaw full of teeth, smaller eyes on stalks, tentacles hanging below."""
    m = Model("hollow_watcher")
    m.part("body", (0, 27, 0), "body")
    m.box((-12, 16, -12), (24, 22, 22), VOID,
          decal={"south": face("#3ff5ff", eyes=1, eye_w=8, eye_h=6, eye_row=0.15, brow="#000000", mask=0.2,
                               mouth="#000000", mouth_row=0.68, mouth_w=20, mouth_h=5, teeth="#cfe8ea")})
    m.box((-10, 37, -10), (20, 3, 18), VOID)
    m.box((-10, 14, -10), (20, 3, 18), VOID)
    for x, z, h, a in ((-9, 0, 10, ("z", 22.5)), (-3, -6, 13, ("x", -22.5)), (5, 2, 9, ("z", -22.5)),
                       (1, -9, 8, ("x", -45)), (-7, -8, 7, ("x", -22.5))):
        m.box((x, 39, z), (2, h, 2), VOID, rot=a)
    for x, y, z in ((-14, 50, 3), (-3, 53, -9), (9, 49, 4), (5, 47, -13), (-12, 46, -11)):
        m.box((x, y - 3, z - 1), (5, 5, 5), VOID, decal={"south": face("#3ff5ff", eyes=1, eye_w=3, eye_h=2,
                                                                       eye_row=0.3, brow=None, mask=0.3)})
    for i, (x, z, h) in enumerate(((-9, 4, 16), (-4, 6, 18), (3, 6, 15), (8, 3, 17), (-8, -6, 14), (6, -7, 16),
                                   (-1, -9, 15))):
        m.part(f"tentacle{i}", (x + 1, 15, z + 1), "tentacle", phase=i * 0.9)
        m.box((x, 15 - h, z), (2, h, 2), SCULK, rot=("x", -22.5 if z > 0 else 22.5, (x + 1, 15, z + 1)))
    m.use("body")
    m.pair((-14, 22, -4), (2, 9, 2), SCULK, rot=("z", 45))
    return m


def guard_face(eye, mouth="#050505", teeth="#d6d0bc", brow="#000000"):
    return face(eye, eye_w=2, eye_h=2, eye_row=0.3, gap=2, brow=brow, mask=0.4, mouth=mouth, mouth_row=0.7,
                mouth_w=6, mouth_h=2, teeth=teeth)


def vault_drowned():
    """A deep-sea fish man: a huge toothy fish head with a glowing lure, fins and webbed claws."""
    m = Model("vault_drowned")
    m.part("leg", (-2.5, 9, 0), "leg", mirror=True)
    m.pair((-4, 0, -2), (3, 9, 4), SCALES_SEA)
    m.part("body", (0, 9, 0), "body")
    m.box((-5, 9, -4), (10, 11, 7), SCALES_SEA)
    m.box((-0.5, 14, -7), (1, 9, 6), FIN)
    m.part("head", (0, 16, 3), "head")
    m.box((-6, 13, 1), (12, 11, 11), SKIN_DROWNED,
          decal={"south": face("#5fffe0", eye_w=2, eye_h=2, eye_row=0.12, gap=6, brow="#000000", mask=0.5,
                               mouth="#020807", mouth_row=0.45, mouth_w=12, mouth_h=5, teeth="#d0e8dc")})
    m.pair((-8, 16, 4), (3, 6, 5), FIN, rot=("z", 22.5))
    m.box((-0.5, 24, 5), (1, 8, 1), SKIN_DROWNED, rot=("x", 22.5))
    m.box((-1, 30, 9), (2, 2, 2), Mat(["#8ffff0", "#c4fff8", "#ffffff", "#ffffff"], cell=1))
    m.part("arm", (-6.5, 18, 0.5), "arm", mirror=True)
    m.pair((-8, 5, -1), (3, 14, 3), SKIN_DROWNED)
    claws(m, -6.5, 5, 0.5, BONE, n=2, length=3, spread=1.5)
    return m


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
    """A floating armoured ghost: helmet and breastplate over a fading tail of cloth, wings and a spear."""
    m = Model("sky_sentry")
    m.part("body", (0, 14, 0), "body")
    m.part("head", (0, 22, 0), "head")
    m.box((-4, 22, -4), (8, 8, 8), QUARTZ, decal={"south": visor("#7ff8ff")})
    m.box((-1, 29, -4), (2, 5, 9), GOLD, rot=("x", -22.5))
    m.use("body")
    m.box((-5, 12, -3), (10, 10, 6), QUARTZ)
    m.box((-5.5, 12, -3.5), (11, 2, 7), GOLD)
    m.pair((-8, 19, -3), (4, 4, 6), QUARTZ)
    m.part("arm", (-6.5, 19, -0.5), "arm", mirror=True)
    m.pair((-8, 9, -2), (3, 11, 3), IRON_DARK)
    claws(m, -6.5, 9, 0.5, GOLD_RAW, n=2, length=2, spread=1.5)
    ghost = Mat(["#3a4a66", "#53668a", "#7088b0", "#9ab0d4"], "bands")
    m.part("tail", (0, 12, 0), "tail")
    m.box((-4, 7, -2.5), (8, 5, 5), ghost)
    m.box((-3, 3, -2), (6, 4, 4), ghost)
    m.box((-2, 0, -1.5), (4, 3, 3), ghost)
    m.part("wing", (-4, 24, -3), "wing", mirror=True)
    m.pair((-15, 12, -3), (9, 14, 1), FEATHER, rot=("z", -22.5, (-4, 24, -3)))
    m.pair((-19, 6, -3), (5, 12, 1), FEATHER, rot=("z", -45, (-12, 16, -3)))
    m.part("spear", (6.5, 19, -0.5), "static", parent="arm_l")
    m.box((8, 2, 1), (1, 34, 1), WOOD)
    m.box((7.5, 36, 0.5), (2, 7, 2), Mat(["#5fb8d8", "#8fdcf2", "#c8f4ff", "#ffffff"], cell=1))
    return m


def forge_brute():
    """A gorilla-like brute: tiny legs, a massive chained chest, knuckles on the ground, a small head sunk low."""
    m = Model("forge_brute")
    m.part("leg", (-2.5, 6, 0), "leg", mirror=True)
    m.pair((-4, 0, -3), (3, 6, 4), BLACKSTONE)
    m.part("body", (0, 6, 0), "body")
    m.box((-7, 5, -5), (14, 12, 9), CHAIN)
    m.box((-8, 15, -6), (16, 6, 11), BLACKSTONE)
    for x in (-6, -2, 2):
        m.box((x, 20, -4), (2, 4, 2), BONE_DARK, rot=("x", -22.5))
    m.part("head", (0, 14, 5), "head")
    m.box((-3.5, 11, 4), (7, 7, 6), BLACKSTONE,
          decal={"south": face("#ffb52a", eye_w=2, eye_h=1, eye_row=0.25, gap=1, brow="#000000", mask=0.4,
                               mouth="#ff5a10", mouth_row=0.6, mouth_w=5, mouth_h=2, teeth="#1a1416")})
    m.pair((-4.5, 13, 7), (1, 3, 1), BONE)
    m.part("arm", (-11, 19, 0.5), "arm", mirror=True)
    m.pair((-14, 4, -3), (6, 16, 7), BLACKSTONE)
    m.pair((-14.5, 0, -3.5), (7, 5, 8), IRON_DARK)
    return m


def tomb_husk():
    """A jackal-headed tomb guardian: black jackal head with tall ears and a toothy snout, gold collar, wraps."""
    m = Model("tomb_husk")
    p = humanoid(m, (4, 12, 4), (8, 12, 4), (3, 15, 3), (7, 7, 7), WRAPS, MUMMY_RIBS, WRAPS, JACKAL,
                 face("#ffd02a", eye_w=2, eye_h=1, eye_row=0.35, gap=2, brow="#000000", mask=0.6),
                 head_fwd=2, head_drop=1)
    m.use("arm")
    claws(m, p["hand_x"], p["hand_y"], 0.5, BONE, n=2, length=3, spread=1.5)
    m.use("head")
    m.box((-2, 23, 5.5), (4, 3, 5), JACKAL, decal={"south": snout("#000000", "#e8e0c8")})
    m.pair((-3.5, 30, 0), (2, 6, 1), JACKAL, rot=("x", -22.5))
    m.use("body")
    m.box((-5, 21, -2.5), (10, 2, 6), GOLD, decal={"south": stripes("#18307e", every=2)})
    m.pair((-5.5, 2, 1), (1, 11, 1), WRAPS_DARK)
    m.box((-1, 14, 2.5), (2, 9, 1), WRAPS_DARK)
    m.part("staff", (p["hand_x"], 23, 0), "static", parent="arm_r")
    m.box((-8, 0, 1), (1, 20, 1), GOLD_RAW)
    m.box((-9, 20, 0), (3, 3, 3), GOLD_RAW, decal={"south": spot("#3a62cc", 0.5, 0.5)})
    return m


def ice_stray():
    m = Model("ice_stray")
    bone = Mat(["#7890a4", "#9cb4c6", "#bed2e0", "#e2eef8"], cell=1)
    m.part("leg", (-2, 12, 0), "leg", mirror=True)
    m.pair((-3, 0, -1), (2, 12, 2), bone)
    m.part("body", (0, 12, 0), "body")
    m.box((-4, 12, -2), (8, 12, 4), Mat(["#7890a4", "#9cb4c6", "#121c26", "#1c2a38"], "ribs"))
    m.part("arm", (-5, 23, 0), "arm", mirror=True)
    m.pair((-6, 9, -1), (2, 15, 2), bone)
    claws(m, -5, 9, 0.5, ICE_RAW, n=2, length=3, spread=1)
    m.part("head", (0, 23, 2), "head")
    m.box((-4, 22, -2), (8, 8, 8), bone,
          decal={"south": face("#a8faff", eye_w=2, eye_h=2, eye_row=0.3, gap=2, brow="#121c26", mask=0.25,
                               mouth="#0c141c", mouth_row=0.72, mouth_w=6, mouth_h=2, teeth="#e2eef8")})
    m.use("body")
    m.box((-5, 20, -4), (10, 6, 6), Mat(["#0e1620", "#16222e", "#1e2e3e", "#283c50"], "fur"))
    m.pair((-7, 22, -2), (3, 5, 4), ICE_RAW, rot=("z", 22.5))
    m.use("head")
    for x, h in ((-3, 4), (-1, 6), (1, 5)):
        m.box((x, 30, 0), (2, h, 2), ICE_RAW)
    m.part("bow", (5, 23, 0), "static", parent="arm_l")
    m.box((6, 8, 1), (1, 18, 1), WOOD)
    m.box((6, 7, 1), (1, 1, 2), WOOD)
    m.box((6, 26, 1), (1, 1, 2), WOOD)
    return m


def stone_crawler():
    m = Model("stone_crawler")
    m.part("head", (0, 2, 6), "head")
    m.box((-3.5, 0, 6), (7, 4, 4), STONE, decal={"south": face("#ff2a1a", eye_w=1, eye_h=1, eye_row=0.2, gap=3,
                                                                 mouth="#050505", mouth_row=0.6, mouth_w=5,
                                                                 mouth_h=2, teeth="#d8d2bc")})
    m.pair((-4, 0, 9), (1, 1, 3), BONE, rot=("y", -22.5, (-3.5, 0.5, 9)))
    m.use("body")
    m.box((-4, 0, 1), (8, 5, 5), STONE)
    m.box((-3.5, 0, -4), (7, 4.5, 5), STONE)
    m.part("tail", (0, 2, -6), "tail")
    m.box((-2.5, 0, -8), (5, 3.5, 4), STONE)
    m.box((-1.5, 0, -11), (3, 2.5, 3), STONE)
    for i, z in enumerate((2, -2, -6)):
        m.part(f"leg{i}", (-4, 1, z), "leg", mirror=True, phase=i * 2.1)
        m.pair((-7, 0, z), (3, 1, 1), STONE, rot=("z", 22.5, (-4, 1, z)))
    m.use("body")
    for x, z, h in ((-2, 2, 3), (1, -2, 3), (-1, -6, 2)):
        m.box((x, 4, z), (1, h, 1), AMETHYST, rot=("x", -22.5))
    return m


def scarab():
    m = Model("scarab")
    shell = Mat(["#4a320a", "#7a5410", "#a8781a", "#e0b040"], cell=1)
    m.box((-5, 1, -6), (10, 5, 12), shell)
    m.box((-0.5, 5.9, -6), (1, 0.2, 12), BEETLE)
    m.part("head", (0, 3, 6), "head")
    m.box((-3, 1, 6), (6, 4, 3), BEETLE, decal={"south": face("#7dff6a", eye_w=1, eye_h=1, eye_row=0.2, gap=2,
                                                                mask=None, brow=None)})
    m.pair((-4, 1.5, 9), (1, 1, 4), BEETLE, rot=("y", -22.5, (-3, 2, 9)))
    m.box((-0.5, 4, 7), (1, 4, 1), BEETLE, rot=("x", 22.5))
    for i, z in enumerate((3, -1, -5)):
        m.part(f"leg{i}", (-5, 1, z), "leg", mirror=True, phase=i * 2.1)
        m.pair((-8, 0, z), (3, 1, 1), BEETLE, rot=("z", 22.5, (-5, 1, z)))
    return m


def sculk_lurker():
    m = Model("sculk_lurker")
    leg = Mat(["#020b0d", "#051418", "#082028", "#0d3038"])
    m.part("head", (0, 5, 2), "head")
    m.box((-4, 3, 0), (8, 5, 7), SCULK, decal={"south": chain(
        face("#4ff3ff", eye_w=1, eye_h=1, eye_row=0.2, gap=2, mask=None, brow=None, mouth="#000000", mouth_row=0.55,
             mouth_w=6, mouth_h=2, teeth="#cfe8ea"), spot("#4ff3ff", 0.15, 0.1), spot("#4ff3ff", 0.85, 0.1))})
    m.pair((-3, 2, 7), (1, 2, 1), BONE)
    m.use("body")
    m.box((-5, 3, -10), (10, 7, 10), SCULK)
    for i, z in enumerate((5, 2, -2, -6)):
        m.part(f"leg{i}", (-4, 6.5, z), "leg", mirror=True, phase=i * 1.6)
        m.pair((-14, 6, z), (10, 1, 1), leg, rot=("z", 22.5, (-4, 6.5, z)))
        m.pair((-14.5, 0, z), (1, 9, 1), leg)
    return m


def jungle_stalker():
    m = Model("jungle_stalker")
    m.part("body", (0, 9, 0), "body")
    m.box((-4, 6, -10), (8, 7, 20), FUR_BLACK)
    for z in (6, -9):
        m.part("legf" if z > 0 else "legb", (-2.5, 7, z + 1.5), "leg_front" if z > 0 else "leg_back", mirror=True)
        m.pair((-4, 0, z), (3, 7, 3), FUR_BLACK)
        claws(m, -2.5, 1, z + 3, BONE, n=2, length=1, spread=1)
    m.part("head", (0, 9, 10), "head")
    m.box((-4, 5, 9), (8, 7, 6), FUR_BLACK, decal={"south": face("#a8ff3a", eye_w=2, eye_h=1, eye_row=0.3, gap=2,
                                                                   brow="#000000")})
    m.box((-2.5, 5, 15), (5, 4, 3), FUR_BLACK, decal={"south": snout("#000000", "#e8e8d8")})
    m.pair((-4, 12, 10), (2, 3, 1), FUR_BLACK, rot=("z", 22.5))
    m.pair((-2, 3, 17), (1, 3, 1), BONE)
    m.part("tail", (0, 11, -10), "tail")
    m.box((-1, 10, -19), (2, 2, 10), FUR_BLACK, rot=("x", 22.5))
    m.use("body")
    m.box((-4.5, 12.5, -6), (9, 1, 10), LEAVES)
    return m


def vine_creeper():
    m = Model("vine_creeper")
    m.box((-5, 2, -6), (10, 6, 12), LEAVES)
    m.part("head", (0, 4, 5), "head")
    m.box((-3, 1, 5), (6, 6, 4), VINE, decal={"south": face("#ff3ad0", eyes=1, eye_w=2, eye_h=2, eye_row=0.15,
                                                              mask=0.4, mouth="#000000", mouth_row=0.6, mouth_w=6,
                                                              mouth_h=2, teeth="#d8f0c0")})
    for i, z in enumerate((3, -2, -6)):
        m.part(f"leg{i}", (-5, 3, z), "leg", mirror=True, phase=i * 2.1)
        m.pair((-11, 0, z), (6, 2, 1), VINE, rot=("z", 22.5, (-5, 3, z)))
        m.pair((-6, 6, z), (2, 4, 1), THORN, rot=("z", 22.5))
    m.part("flower", (0, 8, -1), "tail")
    m.box((-1, 8, -2), (2, 4, 2), VINE)
    m.box((-2, 12, -3), (4, 2, 4), Mat(["#6a0e3a", "#a01a58", "#d42a7e", "#ff6ab0"], cell=1))
    return m


def gale_phantom():
    m = Model("gale_phantom")
    m.box((-3, 0, -6), (6, 4, 12), STORM_BODY)
    m.part("head", (0, 2, 6), "head")
    m.box((-3, 0, 6), (6, 4, 5), STORM_BODY, decal={"south": face("#d8ff4a", eye_w=2, eye_h=1, eye_row=0.1, gap=1,
                                                                    mask=None, mouth="#000000", mouth_row=0.5,
                                                                    mouth_w=6, mouth_h=2, teeth="#e0e6ee")})
    m.pair((-3, 4, 6), (1, 1, 5), BONE, rot=("x", 22.5, (-2.5, 4.5, 11)))
    m.part("wing", (-3, 2.5, 0), "wing", mirror=True)
    m.pair((-13, 2, -4), (10, 1, 9), STORM_WING, rot=("z", -22.5, (-3, 2.5, 0)))
    m.part("wingtip", (-12.2, 6.3, 0), "wingtip", parent="wing", mirror=True)
    m.pair((-22, 5.6, -3), (9, 1, 7), STORM_WING, rot=("z", 22.5, (-12.2, 6.3, 0)))
    m.part("tail", (0, 1.5, -6), "tail")
    m.box((-1.5, 0.5, -12), (3, 2, 6), STORM_BODY)
    return m


def ember_imp():
    m = Model("ember_imp")
    m.part("body", (0, 4, 0), "body")
    m.box((-3, 4, -2), (6, 7, 4), EMBER)
    m.part("leg", (-1.5, 4, 0), "leg", mirror=True)
    m.pair((-2.5, 0, -1), (2, 4, 2), EMBER)
    m.part("arm", (-4, 11, 0), "arm", mirror=True)
    m.pair((-5, 4, -1), (2, 7, 2), EMBER)
    claws(m, -4, 4, 0.5, BONE_DARK, n=2, length=2, spread=1)
    m.part("head", (0, 10, 1), "head")
    m.box((-3.5, 10, -2), (7, 6, 6), EMBER, decal={"south": face("#ffe36b", eye_w=2, eye_h=2, eye_row=0.2, gap=1,
                                                                   brow="#000000", mouth="#ff8a1f", mouth_row=0.6,
                                                                   mouth_w=5, mouth_h=2, teeth="#1e0905")})
    m.pair((-4.5, 15, 0), (1, 5, 1), BONE_DARK, rot=("z", 22.5))
    m.part("wing", (-3, 10, -2), "wing", mirror=True)
    m.pair((-11, 6, -2), (8, 7, 1), WING_DARK, rot=("z", -22.5, (-3, 10, -2)))
    m.part("tail", (0, 4, -2), "tail")
    m.box((-0.5, 3, -7), (1, 1, 5), EMBER)
    m.box((-1, 2.5, -8), (2, 2, 1), MAGMA)
    return m


def frostbite_bear():
    m = Model("frostbite_bear")
    fur = Mat(["#7d93a6", "#a2b6c6", "#c4d4e0", "#e4eef4"], "fur")
    m.part("body", (0, 15, 0), "body")
    m.box((-7, 9, -11), (14, 13, 22), fur)
    m.box((-7.5, 13, 5), (15, 13, 8), fur)
    for z in (5, -10):
        m.part("legf" if z > 0 else "legb", (-4.5, 10, z + 2.5), "leg_front" if z > 0 else "leg_back", mirror=True)
        m.pair((-7, 0, z), (5, 10, 5), fur)
        claws(m, -4.5, 1, z + 5, Mat(["#1a1d22", "#2a2e35", "#3a3f48", "#4a505a"]), n=2, length=1, spread=2)
    m.part("head", (0, 13, 12), "head")
    m.box((-5, 8, 12), (10, 9, 7), fur, decal={"south": face("#7ff6ff", eye_w=2, eye_h=1, eye_row=0.25, gap=4,
                                                               brow="#2a3440")})
    m.box((-3, 7, 19), (6, 6, 4), fur, decal={"south": snout("#14181e", "#ffffff")})
    m.pair((-5, 17, 14), (2, 2, 2), fur)
    m.use("body")
    for z, h in ((-8, 6), (-4, 8), (0, 9), (4, 7)):
        m.box((-1.5, 23, z), (3, h, 3), ICE_RAW, rot=("x", -22.5))
    return m


def shadow_echo():
    m = Model("shadow_echo")
    m.part("head", (0, 17, 1), "head")
    m.box((-4, 16, -3), (8, 8, 8), SHADOW, decal={"south": face("#d39bff", eye_w=2, eye_h=2, eye_row=0.3, gap=2,
                                                                  brow="#000000", mask=0.2, mouth="#000000",
                                                                  mouth_row=0.7, mouth_w=6, mouth_h=2,
                                                                  teeth="#3a2c4a")})
    m.box((-5, 16, -4), (10, 10, 3), SHADOW)
    m.box((-5, 24, -4), (10, 2, 9), SHADOW)
    m.use("body")
    m.box((-4.5, 8, -2.5), (9, 9, 5), SHADOW)
    m.part("tail", (0, 8, 0), "tail")
    m.box((-3.5, 3, -2), (7, 5, 4), SHADOW)
    m.box((-2, 0, -1.5), (4, 3, 3), SHADOW)
    m.part("arm", (-6.5, 17, 0), "arm", mirror=True)
    m.pair((-8, 5, -1), (3, 13, 3), SHADOW, rot=("z", 22.5, (-6.5, 17, 0)))
    return m


def tempest_lord():
    """The Tempest Keep's lord: a towering storm knight in blue-black plate and green copper, a cape of storm cloud,
    a crown of lightning and a storm axe."""
    m = Model("tempest_lord")
    m.part("leg", (-5, 18, 0), "leg", mirror=True)
    m.pair((-9.5, 0, -4.5), (8, 6, 10), STORM_COPPER)
    m.pair((-9, 6, -4), (7, 12, 8), STORM_ARMOR)
    m.pair((-9.5, 12, 3), (8, 3, 2), STORM_COPPER)
    m.part("body", (0, 18, 0), "body")
    m.box((-10.5, 17, -6), (21, 3, 12), STORM_COPPER)
    m.box((-10, 20, -5.5), (20, 8, 11), STORM_ARMOR)
    core = spot("#d6fbff", 0.5, 0.45, 4, 4, ring="#2fe6f2")
    m.box((-12, 28, -6.5), (24, 12, 13), STORM_ARMOR, decal={"south": core})
    # Shoulders: heavy copper plates with lightning spikes.
    m.pair((-18, 35, -7.5), (9, 7, 15), STORM_COPPER)
    m.pair((-16, 42, -3), (2, 7, 2), LIGHTNING, rot=("z", 22.5))
    m.pair((-13, 42, 1), (2, 5, 2), LIGHTNING, rot=("x", -22.5))
    # A cape of storm cloud down the back.
    m.part("cape", (0, 39, -7), "tail")
    m.box((-11, 6, -9.5), (22, 33, 2), STORM_CAPE)
    m.box((-12, 3, -10.5), (24, 6, 2), STORM_CAPE, rot=("x", 22.5, (0, 9, -9.5)))
    # Arms in plate, copper gauntlets.
    m.use("body")
    m.part("arm", (-15, 37, 0), "arm", mirror=True)
    m.pair((-19, 21, -4), (8, 17, 8), STORM_ARMOR)
    m.pair((-19.5, 13, -4.5), (9, 8, 9), STORM_COPPER)
    # A helm with a slit of storm-light for eyes, and a crown of lightning.
    m.part("head", (0, 40, 1), "head")
    m.box((-5.5, 40, -4), (11, 11, 10), STORM_ARMOR,
          decal={"south": maw("#a8f6ff", brow="#04060a", mouth="#04060a", teeth="#2a3d5e", eye_w=3, eye_h=1, eye_row=0.36,
                              gap=2, mouth_row=0.7, mouth_w=6, mouth_h=2, mask=0.5)})
    m.box((-6, 47, -4.5), (12, 2, 11), STORM_COPPER)
    for x, h, r in ((-5, 7, 22.5), (-2, 10, 0), (1, 10, 0), (4, 7, -22.5)):
        m.box((x - 0.5, 49, 0), (1.5, h, 1.5), LIGHTNING, rot=("z", r, (x, 49, 0)) if r else None)
    # The storm axe in the right hand, crackling along its edge.
    m.part("axe", (15, 37, 0), "static", parent="arm_l")
    m.box((18, 0, -1), (2, 36, 2), WOOD)
    m.box((14, 26, -6), (10, 10, 2), STORM_ARMOR)
    m.box((12.5, 24, -7), (2, 14, 4), LIGHTNING)
    m.box((17, 34, -1.5), (4, 4, 3), STORM_COPPER)
    return m


def storm_wisp():
    """A guard: a crackling ball of cloud with lightning arms."""
    m = Model("storm_wisp")
    m.part("body", (0, 8, 0), "body")
    m.box((-5, 4, -5), (10, 10, 10), STORM_CAPE,
          decal={"south": maw("#d6fbff", brow="#04060a", eye_w=2, eye_h=2, eye_row=0.35, gap=2, mouth_row=0.7, mouth_w=4, mouth_h=1)})
    m.box((-3, 13, -3), (6, 4, 6), STORM_CAPE)
    m.part("arm", (-6, 10, 0), "arm", mirror=True)
    m.pair((-9, 2, -0.5), (1.5, 9, 1.5), LIGHTNING, rot=("z", -22.5, (-6, 10, 0)))
    m.box((-1, 0, -1), (2, 4, 2), LIGHTNING)
    return m


def tempest_shade():
    """A guard: a hooded shade in copper and storm cloud."""
    m = Model("tempest_shade")
    m.part("body", (0, 6, 0), "body")
    m.box((-3, 0, -2), (6, 9, 4), STORM_CAPE)
    m.part("head", (0, 9, 0), "head")
    m.box((-3, 9, -3), (6, 6, 6), STORM_ARMOR,
          decal={"south": maw("#a8f6ff", brow="#04060a", eye_w=1, eye_h=1, eye_row=0.45, gap=2, mouth_row=0.8, mouth_w=2, mouth_h=1)})
    m.part("arm", (-4, 8, 0), "arm", mirror=True)
    m.pair((-5, 2, -1), (2, 7, 2), STORM_COPPER)
    return m


MODELS = {
    "deepslate_colossus": (deepslate_colossus, 4.3), "drowned_warden": (drowned_warden, 3.3),
    "storm_phantom": (storm_phantom, 2.4), "forgemaster": (forgemaster, 3.7), "sand_colossus": (sand_colossus, 4.2),
    "frost_titan": (frost_titan, 4.8), "thornback_beast": (thornback_beast, 3.1), "hollow_watcher": (hollow_watcher, 4.6),
    "vault_drowned": (vault_drowned, 1.95), "sky_sentry": (sky_sentry, 2.0), "forge_brute": (forge_brute, 1.95),
    "tomb_husk": (tomb_husk, 1.95), "ice_stray": (ice_stray, 2.0), "stone_crawler": (stone_crawler, 0.4),
    "scarab": (scarab, 0.4), "sculk_lurker": (sculk_lurker, 0.55), "jungle_stalker": (jungle_stalker, 0.95),
    "vine_creeper": (vine_creeper, 0.55), "gale_phantom": (gale_phantom, 0.6), "ember_imp": (ember_imp, 0.6),
    "frostbite_bear": (frostbite_bear, 1.4), "shadow_echo": (shadow_echo, 0.85),
    "tempest_lord": (tempest_lord, 6.2), "storm_wisp": (storm_wisp, 1.8), "tempest_shade": (tempest_shade, 0.8),
}
