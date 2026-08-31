/**
 * Compute covariance ellipse parameters from the 2x2 position block of P.
 * Returns semi-major axis, semi-minor axis, and rotation angle.
 *
 * The eigenvalues of the 2x2 covariance matrix give the squared semi-axes.
 * For a 95% confidence ellipse, scale by chi-square(2, 0.95) = 5.991.
 */
export function covarianceEllipse(
  pxx: number, pyy: number, pxy: number,
  confidenceScale: number = 5.991
): { major: number; minor: number; angle: number } {
  const trace = pxx + pyy;
  const det = pxx * pyy - pxy * pxy;
  const discriminant = Math.sqrt(Math.max(0, trace * trace / 4 - det));

  const lambda1 = trace / 2 + discriminant;
  const lambda2 = trace / 2 - discriminant;

  const major = Math.sqrt(Math.max(0, lambda1) * confidenceScale);
  const minor = Math.sqrt(Math.max(0, lambda2) * confidenceScale);
  const angle = Math.atan2(2 * pxy, pxx - pyy) / 2;

  return { major, minor, angle };
}

/**
 * Convert polar (range, bearing) observation from a sensor position
 * to Cartesian coordinates for rendering.
 */
export function polarToCartesian(
  sensorX: number, sensorY: number,
  range: number, azimuth: number
): { x: number; y: number } {
  return {
    x: sensorX + range * Math.cos(azimuth),
    y: sensorY + range * Math.sin(azimuth),
  };
}
