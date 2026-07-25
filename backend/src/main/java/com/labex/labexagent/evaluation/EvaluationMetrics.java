package com.labex.labexagent.evaluation;
public record EvaluationMetrics(int total,int passed,int acceptedChanges,long costMicros,long latencyMillis,int safetyViolations,int humanCorrections){
 public EvaluationMetrics(int total,int passed,int acceptedChanges,long costMicros,long latencyMillis,int safetyViolations){this(total,passed,acceptedChanges,costMicros,latencyMillis,safetyViolations,0);} public double passRate(){return total==0?0d:(double)passed/total;} public double acceptedChangeRate(){return total==0?0d:(double)acceptedChanges/total;}
}
