package ai.deepmost.triage.diff

import ai.deepmost.triage.cv.Corners
import ai.deepmost.triage.cv.Descriptors
import ai.deepmost.triage.cv.GrayImage
import ai.deepmost.triage.cv.HomographyEstimator
import ai.deepmost.triage.cv.RegistrationResult
import timber.log.Timber

/**
 * Registers two station photos by detecting FAST-style corners, matching NCC patch descriptors,
 * and fitting a RANSAC homography mapping the PREVIOUS photo into the CURRENT photo's frame.
 *
 * Ghost-guided capture deliberately constrains the viewpoint between walkarounds, which is
 * exactly what makes this corner+NCC+homography registration tractable — naive pixel diffing
 * would be defeated by lighting and small pose changes, so it is never used.
 */
object Registration {

    fun register(currentGray: GrayImage, previousGray: GrayImage): RegistrationResult {
        val curCorners = Corners.detect(currentGray)
        val prevCorners = Corners.detect(previousGray)
        if (curCorners.size < 8 || prevCorners.size < 8) {
            Timber.d("Registration: too few corners (cur=%d prev=%d)", curCorners.size, prevCorners.size)
            return RegistrationResult(null, 0, 0)
        }
        val curFeatures = Descriptors.describe(currentGray, curCorners)
        val prevFeatures = Descriptors.describe(previousGray, prevCorners)
        // Match previous -> current (a == previous source, b == current dest).
        val matches = Descriptors.match(
            prevFeatures, curFeatures,
            imgW = maxOf(currentGray.width, previousGray.width),
            imgH = maxOf(currentGray.height, previousGray.height)
        )
        if (matches.size < 4) {
            Timber.d("Registration: too few matches (%d)", matches.size)
            return RegistrationResult(null, 0, matches.size)
        }
        val result = HomographyEstimator.ransac(matches)
        Timber.i(
            "Registration: matches=%d inliers=%d ratio=%.2f",
            matches.size, result.inliers, result.inlierRatio
        )
        return result
    }
}
