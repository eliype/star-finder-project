"""On-device solver: astrometry.net upload + Polaris offset from the solved FITS.

Runs under Chaquopy (no matplotlib). Derived from draw_polaris_line.py.
"""
import json
import os
import tempfile
import time

# astropy writes config/cache files at import time; point them at a writable dir on Android.
os.environ.setdefault("XDG_CACHE_HOME", tempfile.gettempdir())
os.environ.setdefault("XDG_CONFIG_HOME", tempfile.gettempdir())

import numpy as np
import requests
from astropy.coordinates import SkyCoord
from astropy.io import fits
from astropy.wcs import WCS
import astropy.units as u

API = "https://nova.astrometry.net/api"
POLARIS = SkyCoord("02h31m49.09s", "+89d15m50.8s")  # J2000
POLE = SkyCoord(0 * u.deg, 90 * u.deg)
CIRCLE_FRACTION = 0.02  # circle radius as a fraction of image width


def solve(image_path, api_key, out_fits_path, timeout_s=600, progress=None):
    """Upload an image to astrometry.net and download the solved FITS.

    progress: optional status sink: a Python callable(str) or a Java Consumer<String>
    (called via .accept). Returns out_fits_path.
    """
    def say(msg):
        if progress is None:
            return
        if hasattr(progress, "accept"):
            progress.accept(msg)
        else:
            progress(msg)

    say("Logging in")
    r = requests.post(API + "/login",
                      data={"request-json": json.dumps({"apikey": api_key})}, timeout=30)
    r.raise_for_status()
    j = r.json()
    if j.get("status") != "success":
        raise RuntimeError("astrometry.net login failed: %s" % j)
    session = j["session"]

    say("Uploading image")
    args = {"session": session, "publicly_visible": "n", "allow_commercial_use": "n",
            "allow_modifications": "n"}
    with open(image_path, "rb") as f:
        r = requests.post(API + "/upload",
                          data={"request-json": json.dumps(args)},
                          files={"file": (os.path.basename(image_path), f)}, timeout=120)
    r.raise_for_status()
    j = r.json()
    if j.get("status") != "success":
        raise RuntimeError("upload failed: %s" % j)
    subid = j["subid"]

    say("Waiting for solver")
    deadline = time.time() + timeout_s
    job_id = None
    while time.time() < deadline:
        time.sleep(3)
        if job_id is None:
            jobs = requests.get(API + "/submissions/%s" % subid, timeout=30).json().get("jobs", [])
            job_id = next((x for x in jobs if x is not None), None)
            continue
        status = requests.get(API + "/jobs/%s" % job_id, timeout=30).json().get("status")
        if status == "success":
            break
        if status == "failure":
            raise RuntimeError("astrometry.net could not solve this image")
    else:
        raise TimeoutError("astrometry.net timed out")

    say("Downloading solution")
    r = requests.get("https://nova.astrometry.net/new_fits_file/%s" % job_id, timeout=120)
    r.raise_for_status()
    with open(out_fits_path, "wb") as f:
        f.write(r.content)
    return out_fits_path


def polaris_offset(fits_path):
    """Offset from the image center to Polaris, in the image frame.

    Image frame: x right, y up (FITS row 0 = bottom). `theta_deg` is the angle of
    the direction to Polaris measured clockwise from image-up, derived from the WCS
    itself so rotation and mirroring are handled implicitly.
    """
    with fits.open(fits_path) as hdul:
        header = hdul[0].header
        data = hdul[0].data
    w = WCS(header, naxis=2)
    if data is not None and data.ndim == 3:
        H, W = data.shape[1], data.shape[2]
    else:
        H, W = int(header["NAXIS2"]), int(header["NAXIS1"])

    cx, cy = (W - 1) / 2, (H - 1) / 2
    center = w.pixel_to_world(cx, cy)
    sep = center.separation(POLARIS)
    pa = center.position_angle(POLARIS)

    # Direction to Polaris in pixel space, from a small step along the great circle.
    step = min(sep.deg, 0.05)
    p = center.directional_offset_by(pa, step * u.deg)
    sx, sy = (float(v) for v in w.world_to_pixel(p))
    theta = float(np.degrees(np.arctan2(sx - cx, sy - cy)) % 360)  # clockwise from up

    px, py = (float(v) for v in w.world_to_pixel(POLARIS))
    inside = bool(-0.5 <= px <= W - 0.5 and -0.5 <= py <= H - 0.5)

    return {
        "ra_deg": float(center.ra.deg),
        "dec_deg": float(center.dec.deg),
        "sep_deg": float(sep.deg),
        "pa_deg": float(pa.deg),
        "theta_deg": theta,
        "dra_deg": float((POLARIS.ra.deg - center.ra.deg + 180) % 360 - 180),
        "ddec_deg": float(POLARIS.dec.deg - center.dec.deg),
        "pixel_x": px,
        "pixel_y": py,
        "width": int(W),
        "height": int(H),
        "inside": inside,
        "circle_radius_px": CIRCLE_FRACTION * W,
        "polaris_pole_offset_deg": float(POLARIS.separation(POLE).deg),
    }


def polaris_offset_json(fits_path):
    return json.dumps(polaris_offset(fits_path))
