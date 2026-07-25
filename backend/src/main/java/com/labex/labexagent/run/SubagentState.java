package com.labex.labexagent.run;
import java.util.EnumSet;
public enum SubagentState {
 QUEUED, RUNNING, WAITING_USER, COMPLETED, FAILED, CANCELLED;
 public boolean mayTransitionTo(SubagentState target){
  return switch(this){
   case QUEUED -> EnumSet.of(RUNNING,CANCELLED,FAILED).contains(target);
   case RUNNING -> EnumSet.of(WAITING_USER,COMPLETED,FAILED,CANCELLED).contains(target);
   case WAITING_USER -> EnumSet.of(RUNNING,CANCELLED,FAILED).contains(target);
   default -> false;
  };
 }
 public String persisted(){return name().toLowerCase(java.util.Locale.ROOT);}
}
