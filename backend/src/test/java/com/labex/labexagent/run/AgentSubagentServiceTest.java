package com.labex.labexagent.run;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;import static org.junit.jupiter.api.Assertions.assertThrows;
import com.labex.entity.AgentSubagent;import com.labex.mapper.AgentSubagentMapper;import org.junit.jupiter.api.Test;import static org.mockito.Mockito.mock;
class AgentSubagentServiceTest {
 @Test void enforcesPersistedTokenBudget(){AgentSubagent agent=new AgentSubagent();agent.setTokenBudget(10);agent.setTokensUsed(8);AgentSubagentService service=new AgentSubagentService(mock(AgentSubagentMapper.class),new SubagentPolicy());assertDoesNotThrow(()->service.consumeTokens(agent,2));assertThrows(IllegalStateException.class,()->service.consumeTokens(agent,1));}
}
