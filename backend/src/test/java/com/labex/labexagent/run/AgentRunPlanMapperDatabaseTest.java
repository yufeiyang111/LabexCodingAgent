package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.labex.entity.AgentRunPlanItem;
import com.labex.mapper.AgentRunPlanItemMapper;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class AgentRunPlanMapperDatabaseTest {

    @Test
    void readsItemsInPositionOrderAndEnforcesOnePositionPerTask() throws Exception {
        DataSource dataSource = dataSource();
        createSchema(dataSource);
        Configuration configuration = new Configuration(
                new Environment("run-plan", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentRunPlanItemMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentRunPlanItemMapper mapper = session.getMapper(AgentRunPlanItemMapper.class);
            mapper.insertPlanItem(row(71L, 1, "Verify"));
            mapper.insertPlanItem(row(71L, 0, "Inspect"));

            List<AgentRunPlanItem> rows = mapper.selectByTaskIdOrderByPosition(71L);
            assertThat(rows).extracting(AgentRunPlanItem::getTitle)
                    .containsExactly("Inspect", "Verify");
            assertThatThrownBy(() -> mapper.insertPlanItem(row(71L, 1, "Duplicate")))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:agent_run_plan;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createSchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_agent_run_plan_item (
                        plan_item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        task_id BIGINT NOT NULL,
                        execution_epoch BIGINT NOT NULL,
                        plan_revision BIGINT NOT NULL,
                        position INT NOT NULL,
                        title VARCHAR(512) NOT NULL,
                        description LONGTEXT,
                        status VARCHAR(32) NOT NULL,
                        create_time DATETIME,
                        update_time DATETIME,
                        CONSTRAINT uk_agent_run_plan_task_position UNIQUE (task_id, position)
                    )
                    """);
        }
    }

    private AgentRunPlanItem row(long taskId, int position, String title) {
        AgentRunPlanItem item = new AgentRunPlanItem();
        item.setTaskId(taskId);
        item.setExecutionEpoch(4L);
        item.setPlanRevision(1L);
        item.setPosition(position);
        item.setTitle(title);
        item.setDescription("");
        item.setStatus(position == 0 ? "in_progress" : "pending");
        return item;
    }
}
