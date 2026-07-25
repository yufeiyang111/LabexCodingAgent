package com.labex.labexagent.evaluation;
import com.google.gson.Gson;import java.io.InputStreamReader;import java.nio.charset.StandardCharsets;import java.util.List;
public final class EvaluationCorpus {
 private static final Gson GSON=new Gson();private EvaluationCorpus(){}
 public static List<Case> loadV1(){try(var stream=EvaluationCorpus.class.getResourceAsStream("/evaluation/v1/corpus.json")){if(stream==null)throw new IllegalStateException("evaluation corpus missing");Case[] cases=GSON.fromJson(new InputStreamReader(stream,StandardCharsets.UTF_8),Case[].class);return List.of(cases);}catch(Exception e){throw new IllegalStateException("evaluation corpus cannot be loaded",e);}}
 public static boolean validates(Case testCase,String output){return output!=null&&output.toLowerCase(java.util.Locale.ROOT).contains(testCase.requiredText().toLowerCase(java.util.Locale.ROOT));}
 public record Case(String id,String category,String requiredText){}
}
