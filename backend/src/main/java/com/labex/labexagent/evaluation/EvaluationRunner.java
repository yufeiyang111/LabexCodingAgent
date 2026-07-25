package com.labex.labexagent.evaluation;
import com.labex.entity.AgentEvaluationRun;import com.labex.mapper.AgentEvaluationRunMapper;import java.time.LocalDateTime;import org.springframework.stereotype.Service;
@Service public class EvaluationRunner {
 private final AgentEvaluationRunMapper mapper;private final ReleaseRegressionGate gate;
 public EvaluationRunner(AgentEvaluationRunMapper mapper,ReleaseRegressionGate gate){this.mapper=mapper;this.gate=gate;}
 public Result runV1(String candidate,EvaluationCaseRunner runner,EvaluationMetrics baseline){int passed=0,accepted=0,safety=0;long cost=0,latency=0;var corpus=EvaluationCorpus.loadV1();for(var testCase:corpus){try{var result=runner.run(testCase);if(EvaluationCorpus.validates(testCase,result.output()))passed++;if(result.acceptedChange())accepted++;cost+=result.costMicros();latency+=result.latencyMillis();safety+=result.safetyViolations();}catch(Exception failure){safety++;}}
  EvaluationMetrics metrics=new EvaluationMetrics(corpus.size(),passed,accepted,cost,latency,safety);AgentEvaluationRun entity=new AgentEvaluationRun();entity.setCorpusVersion("v1");entity.setCandidate(candidate);entity.setTotal(metrics.total());entity.setPassed(metrics.passed());entity.setAcceptedChanges(metrics.acceptedChanges());entity.setCostMicros(metrics.costMicros());entity.setLatencyMillis(metrics.latencyMillis());entity.setSafetyViolations(metrics.safetyViolations());entity.setHumanCorrections(metrics.humanCorrections());entity.setCreateTime(LocalDateTime.now());mapper.insert(entity);return new Result(entity,metrics,baseline==null?new ReleaseRegressionGate.Decision(true,""):gate.evaluate(baseline,metrics));}
 public record Result(AgentEvaluationRun evaluation,EvaluationMetrics metrics,ReleaseRegressionGate.Decision releaseDecision){}
}
