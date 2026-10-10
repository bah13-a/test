package tn.vas.config;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Séparation physique commande / requête (CQRS) : si {@code vas.read-db.url} est renseigné, les transactions en lecture seule
 * (@Transactional(readOnly = true), c.-à-d. tout le côté requête) sont envoyées au réplica PostgreSQL ; tout le reste (commandes, projections,
 * migrations) va à la base primaire. Sans cette propriété : une seule base, comportement inchangé.
 * Compromis assumé : cohérence à terme (le réplica peut retarder de quelques centaines de ms) ; les listes de configuration qui doivent refléter
 * immédiatement une modification restent côté commande (lues sur la primaire).
 */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("!'${vas.read-db.url:}'.isEmpty()")
public class ReadReplicaConfig {
    public static final String PRIMARY = "primary", REPLICA = "replica";

    public static class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {
        public ReadWriteRoutingDataSource(DataSource primary, DataSource replica) {
            setTargetDataSources(Map.of(PRIMARY, primary, REPLICA, replica));
            setDefaultTargetDataSource(primary);
            afterPropertiesSet();
        }

        @Override
        protected Object determineCurrentLookupKey() {
            return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? REPLICA : PRIMARY;
        }
    }

    @Bean
    @Primary
    DataSource routingDataSource(DataSourceProperties primaryProps, @Value("${vas.read-db.url}") String url,
                                 @Value("${vas.read-db.username:${spring.datasource.username:}}") String user,
                                 @Value("${vas.read-db.password:${spring.datasource.password:}}") String password) {
        HikariDataSource primary = primaryProps.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        primary.setPoolName("vas-primary");
        HikariDataSource replica = new HikariDataSource();
        replica.setJdbcUrl(url);
        replica.setUsername(user);
        replica.setPassword(password);
        replica.setReadOnly(true);
        replica.setPoolName("vas-replica");
        // la connexion n'est prise qu'à la première requête : l'indicateur « lecture seule » de la transaction est alors connu
        return new LazyConnectionDataSourceProxy(new ReadWriteRoutingDataSource(primary, replica));
    }
}
