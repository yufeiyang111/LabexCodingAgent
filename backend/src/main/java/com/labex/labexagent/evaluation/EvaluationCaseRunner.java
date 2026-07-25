package com.labex.labexagent.evaluation;
public interface EvaluationCaseRunner { CaseResult run(EvaluationCorpus.Case testCase) throws Exception; record CaseResult(String output,boolean acceptedChange,long costMicros,long latencyMillis,int safetyViolations){} }
