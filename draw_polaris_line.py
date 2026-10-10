import numpy as np
from astropy.io import fits
from astropy.wcs import WCS
from astropy.coordinates import SkyCoord
import astropy.units as u
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

POLARIS = SkyCoord("02h31m49.09s", "+89d15m50.8s")  # J2000
POLE = SkyCoord(0 * u.deg, 90 * u.deg)


def find_polaris(fits_path, out_path=None):
    """Locate Polaris relative to the center of a plate-solved FITS image.

    Returns a dict with the offset to Polaris (separation, position angle,
    dRA/dDec), its pixel position, and whether it falls inside the image.
    If out_path is given, saves the image annotated with the line to Polaris.
    """
    hdu = fits.open(fits_path)[0]
    w = WCS(hdu.header, naxis=2)
    img = np.transpose(hdu.data, (1, 2, 0))  # (H, W, 3), row 0 = bottom (FITS)
    H, W = img.shape[:2]

    cx, cy = (W - 1) / 2, (H - 1) / 2
    center = w.pixel_to_world(cx, cy)

    sep = center.separation(POLARIS)
    pa = center.position_angle(POLARIS)

    # Sample great circle center -> Polaris, project through full WCS (incl. SIP)
    pts = []
    for s in np.linspace(0, sep.deg, 4000):
        p = center.directional_offset_by(pa, s * u.deg)
        x, y = w.world_to_pixel(p)
        if not (-0.5 <= x <= W - 0.5 and -0.5 <= y <= H - 0.5):
            break
        pts.append((float(x), float(y)))
    pts = np.array(pts)
    px, py = (float(v) for v in w.world_to_pixel(POLARIS))
    inside = (-0.5 <= px <= W - 0.5) and (-0.5 <= py <= H - 0.5)

    if out_path:
        fig = plt.figure(figsize=(W / 100, H / 100), dpi=100)
        ax = fig.add_axes([0, 0, 1, 1])
        ax.imshow(img, origin="lower")
        ax.plot(pts[:, 0], pts[:, 1], color="yellow", lw=0.5)
        if inside:
            ax.plot([px], [py], "o", mfc="none", mec="cyan", ms=25, mew=2)
            ax.text(px + 20, py, "Polaris", color="cyan", fontsize=14)
        ax.set_xlim(-0.5, W - 0.5); ax.set_ylim(-0.5, H - 0.5); ax.axis("off")
        fig.savefig(out_path)
        plt.close(fig)

    return {
        "center": center,
        "sep_deg": sep.deg,
        "pa_deg": pa.deg,
        "dra_deg": (POLARIS.ra.deg - center.ra.deg + 180) % 360 - 180,
        "ddec_deg": POLARIS.dec.deg - center.dec.deg,
        "pixel": (px, py),
        "inside": inside,
        "polaris_pole_offset_deg": POLARIS.separation(POLE).deg,
    }


if __name__ == "__main__":
    r = find_polaris("new-image.fits", "new-image_polaris_line.jpeg")
    print("center", r["center"].to_string("hmsdms"))
    print("polaris in image:", r["inside"], "pixel", tuple(round(v, 1) for v in r["pixel"]))
    print(f"dRA  = {r['dra_deg']:+.3f} deg = {r['dra_deg']/15:+.4f} h ({'east' if r['dra_deg']>0 else 'west'})")
    print(f"dDec = {r['ddec_deg']:+.3f} deg ({'north' if r['ddec_deg']>0 else 'south'})")
    print(f"great-circle sep = {r['sep_deg']:.3f} deg, PA = {r['pa_deg']:.2f} deg (N through E)")
    print(f"Polaris-pole offset = {r['polaris_pole_offset_deg']:.3f} deg")
