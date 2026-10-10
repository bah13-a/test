package tn.vas;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tn.vas.config.ReadReplicaConfig.ReadWriteRoutingDataSource;

/** Côté requête sur le réplica, côté commande sur la primaire. */
class ReadWriteRoutingTests {
    @Test
    void readOnlyTransactionsGoToTheReplicaAndOthersToThePrimary() {
        var primary = new DriverManagerDataSource("jdbc:h2:mem:rw_primary;DB_CLOSE_DELAY=-1", "sa", "");
        var replica = new DriverManagerDataSource("jdbc:h2:mem:rw_replica;DB_CLOSE_DELAY=-1", "sa", "");
        new JdbcTemplate(primary).execute("create table who(v varchar(20)); insert into who values ('primary')");
        new JdbcTemplate(replica).execute("create table who(v varchar(20)); insert into who values ('replica')");
        var ds = new LazyConnectionDataSourceProxy(new ReadWriteRoutingDataSource(primary, replica));
        var jdbc = new JdbcTemplate(ds);
        var tm = new DataSourceTransactionManager(ds);

        var ro = new TransactionTemplate(tm);
        ro.setReadOnly(true);
        assertEquals("replica", ro.execute(s -> jdbc.queryForObject("select v from who", String.class)), "requête : réplica");
        assertEquals("primary", new TransactionTemplate(tm).execute(s -> jdbc.queryForObject("select v from who", String.class)), "commande : primaire");
        assertEquals("primary", jdbc.queryForObject("select v from who", String.class), "hors transaction : primaire (migrations, démarrage)");
    }
}
