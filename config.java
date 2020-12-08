import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.Executors;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.PersistenceContext;
import javax.sql.DataSource;

import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.common.jdbc.UnitOfWorkAwareConnectionProviderWrapper;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.jpa.SimpleEntityManagerProvider;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventhandling.tokenstore.jpa.JpaTokenStore;
import org.axonframework.eventsourcing.AggregateFactory;
import org.axonframework.eventsourcing.EventCountSnapshotTriggerDefinition;
import org.axonframework.eventsourcing.SnapshotTriggerDefinition;
import org.axonframework.eventsourcing.Snapshotter;
import org.axonframework.eventsourcing.eventstore.jpa.SQLErrorCodesResolver;
import org.axonframework.serialization.Serializer;
import org.axonframework.spring.eventsourcing.SpringAggregateSnapshotterFactoryBean;
import org.axonframework.spring.eventsourcing.SpringPrototypeAggregateFactory;
import org.axonframework.spring.jdbc.SpringDataSourceConnectionProvider;
import org.axonframework.spring.messaging.unitofwork.SpringTransactionManager;
import org.axonframework.springboot.autoconfig.AxonAutoConfiguration;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import com.softilys.aggregates.ExerciseFrameworkAggregate;

@Configuration
@AutoConfigureAfter(AxonAutoConfiguration.class)
public class AxonConfig {

	@Autowired
	private ConfigCommandProperties configProperties;

	@Bean(name = "cs-ds")
	public DataSource dataSource() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName(configProperties.getJpa().getDriverClassName());
		dataSource.setUrl(configProperties.getJpa().getCommand().getUrl());
		dataSource.setUsername(configProperties.getJpa().getCommand().getUsername());
		dataSource.setPassword(configProperties.getJpa().getCommand().getPassword());
		return dataSource;
	}

	private HibernateJpaVendorAdapter vendorAdaptor() {
		HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
		vendorAdapter.setShowSql(false);
		return vendorAdapter;
	}

	@Bean(name = "cs-emf")
	@PersistenceContext(unitName = "cs", name = "cs-em")
	public LocalContainerEntityManagerFactoryBean entityManagerFactoryBean() {

		LocalContainerEntityManagerFactoryBean entityManagerFactoryBean = new LocalContainerEntityManagerFactoryBean();
		entityManagerFactoryBean.setJpaVendorAdapter(vendorAdaptor());
		entityManagerFactoryBean.setDataSource(dataSource());
		entityManagerFactoryBean.setPersistenceProviderClass(HibernatePersistenceProvider.class);
		entityManagerFactoryBean.setPackagesToScan("org.axonframework.eventsourcing.eventstore.jpa",
				"org.axonframework.eventhandling.tokenstore.jpa", "org.axonframework.modelling.saga.repository.jpa");
		entityManagerFactoryBean.setJpaProperties(additionalProperties());

		return entityManagerFactoryBean;
	}

	Properties additionalProperties() {
		Properties properties = new Properties();
		properties.setProperty("hibernate.hbm2ddl.auto", configProperties.getJpa().getCommand().getDdlAuto());
		properties.setProperty("hibernate.dialect", configProperties.getJpa().getDialect());
		properties.setProperty("hibernate.jdbc.lob.non_contextual_creation",
				configProperties.getJpa().getNonContextualCreation());
		return properties;
	}

	@Bean
	@Primary
	@ConditionalOnMissingBean
	@ConditionalOnBean(DataSource.class)
	public PersistenceExceptionResolver persistenceExceptionResolver(@Qualifier("cs-ds") DataSource dataSource)
			throws SQLException {
		return new SQLErrorCodesResolver(dataSource);
	}

	@Bean("cs-emp")
	@Primary
	@ConditionalOnMissingBean
	public EntityManagerProvider entityManagerProvider(@Qualifier("cs-emf") EntityManager entityManager) {
		return new SimpleEntityManagerProvider(entityManager);
	}

	@Bean(name = "cs-tx")
	@DependsOn("cs-emf")
	public PlatformTransactionManager transactionManager(@Qualifier("cs-emf") EntityManagerFactory entityManagerFactory,
			@Qualifier("cs-ds") DataSource dataSource) {
		JpaTransactionManager jpaTransactionManager = new JpaTransactionManager(entityManagerFactory);
		jpaTransactionManager.setDataSource(dataSource);
		return jpaTransactionManager;
	}

	@Bean
	@Primary
	@ConditionalOnMissingBean
	public ConnectionProvider connectionProvider(@Qualifier("cs-ds") DataSource dataSource) {
		return new UnitOfWorkAwareConnectionProviderWrapper(new SpringDataSourceConnectionProvider(dataSource));
	}

	@Bean
	@Primary
	@ConditionalOnMissingBean
	public TransactionManager axonTransactionManager(
			@Qualifier("cs-tx") PlatformTransactionManager transactionManager) {
		return new SpringTransactionManager(transactionManager);
	}

	@Bean
	public TokenStore tokenStore(Serializer serializer,
			@Qualifier("cs-emp") EntityManagerProvider entityManagerProvider) {
		return JpaTokenStore.builder().serializer(serializer).entityManagerProvider(entityManagerProvider).build();
	}

	@Bean(name = "cs-snapshotter")
	public SpringAggregateSnapshotterFactoryBean snapshotterFactory(
			@Qualifier("cs-tx") PlatformTransactionManager transactionManager) {
		SpringAggregateSnapshotterFactoryBean springAggregateSnapshotterFactoryBean = new SpringAggregateSnapshotterFactoryBean();
		springAggregateSnapshotterFactoryBean.setExecutor(Executors.newSingleThreadExecutor());
		springAggregateSnapshotterFactoryBean.setTransactionManager(transactionManager);
		return springAggregateSnapshotterFactoryBean;
	}

}
