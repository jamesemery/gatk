package org.broadinstitute.hellbender.tools.exome;


import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.apache.commons.math3.stat.descriptive.rank.Median;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Simple caller that
 * 1) estimates the value of log_2 normalized coverage corresponding to copy neutral segments
 * This value is not necessarily equal to zero.  For example, a large amplification on one segment
 * depresses the proportional coverage of all other segments.
 * 2) estimates the variance of targets' normalized coverage within a segment, assuming that
 * this variance is globally shared by all segments
 * 3) calls each segment based on a p-value for the null hypothesis that its targets'
 * normalized coverages are drawn from a normal distribution defined by the mean from 1)
 * and the variance from 2)
 *
 * @author David Benjamin
 */
public final class ReCapSegCaller {

    public static String AMPLIFICATION_CALL = "+";
    public static String DELETION_CALL = "-";
    public static String NEUTRAL_CALL = "0";

    // number of standard deviations from mean required to call an amplification or deletion
    public static final double Z_SCORE_THRESHOLD = 2.0;

    //bounds on log_2 coverage we take as non-neutral a priori
    public static final double NON_NEUTRAL_THRESHOLD = 0.3;

    //bounds on log_2 coverage for high-confidence neutral segments
    public static final double COPY_NEUTRAL_CUTOFF = 0.1;

    private ReCapSegCaller() {} // prevent instantiation

    /**
     * Add calls in-place to a list of segments based on the coverage data in a set of targets
     * Note that segments are modified by being given calls.  Any previous calls are erased.
     *
     * @param targets the collection representing all targets
     * @param segments segments, each of which holds a reference to these same targets
     */
    public static List<CalledInterval> makeCalls(final TargetCollection<TargetCoverage> targets, final List<SimpleInterval> segments) {
        Utils.nonNull(segments, "Can't make calls on a null list of segments.");
        final List<CalledInterval> calls = new ArrayList<>();

        /**
         * estimate the copy-number neutral log_2 coverage as the median over all targets.
         * As a precaution we take the median after filtering extreme coverages that are definitely not neutral
         * i.e. those below -NON_NEUTRAL_COVERAGE or above +NON_NEUTRAL_COVERAGE
         */
        final double []  nonExtremeCoverage = targets.targets()
                .stream()
                .mapToDouble(TargetCoverage::getCoverage)
                .filter(c -> Math.abs(c) < NON_NEUTRAL_THRESHOLD)
                .toArray();
        final double neutralCoverage = new Median().evaluate(nonExtremeCoverage);

        // Get the standard deviation of targets belonging to high-confidence neutral segments
        final double[] nearNeutralCoverage = segments.stream()
                .filter(s -> Math.abs(SegmentUtils.meanTargetCoverage(s, targets) - neutralCoverage) < COPY_NEUTRAL_CUTOFF)
                .flatMap(s -> targets.targets(s).stream())
                .mapToDouble(TargetCoverage::getCoverage)
                .toArray();
        final double neutralSigma = new StandardDeviation().evaluate(nearNeutralCoverage);


        for (final SimpleInterval segment : segments) {
            //Get the number of standard deviations the mean falls from the copy neutral value
            //we should really use the standard deviation of the mean of segment.numTargets() targets
            //which is sigma/ sqrt(numTargets).  However, the underlying segment means vary due to noise not
            //removed by tangent normalization, and such a caller would be too strict.
            //Thus we just use the population standard deviation of targets, and defer a mathematically
            //principled treatment for a later caller
            final double Z = (SegmentUtils.meanTargetCoverage(segment, targets) - neutralCoverage)/neutralSigma;

            final String call = Z < -Z_SCORE_THRESHOLD ? DELETION_CALL: (Z > Z_SCORE_THRESHOLD ? AMPLIFICATION_CALL : NEUTRAL_CALL);
            calls.add(new CalledInterval(segment, call));
        }
        return calls;
    }
}