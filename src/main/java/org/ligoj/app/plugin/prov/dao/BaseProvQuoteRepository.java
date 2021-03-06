/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.dao;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.ligoj.app.plugin.prov.model.AbstractQuote;
import org.ligoj.bootstrap.core.dao.RestRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * {@link AbstractQuote} repository.
 * 
 * @param <C> Quote resource type.
 */
@NoRepositoryBean
public interface BaseProvQuoteRepository<C extends AbstractQuote<?>> extends RestRepository<C, Integer> {

	/**
	 * Return identifiers of all resources linked to the given subscription.
	 *
	 * @param subscription The related subscription identifier.
	 * @return Return identifiers of all resources linked to the given subscription.
	 */
	@Query("SELECT id FROM #{#entityName} WHERE configuration.subscription.id = :subscription")
	List<Integer> findAllIdentifiers(int subscription);

	/**
	 * Delete all instance linked to the given subscription.
	 *
	 * @param subscription The related subscription identifier.
	 */
	@Modifying
	@Query("DELETE FROM #{#entityName} WHERE configuration.id IN"
			+ " (SELECT id FROM ProvQuote WHERE subscription.id = :subscription)")
	void deleteAllBySubscription(int subscription);

	/**
	 * Return the quote details from the related subscription.
	 *
	 * @param subscription The subscription identifier linking the quote.
	 * @return The instance quote details with the optional linked instance.
	 */
	@Query("FROM #{#entityName} AS qi INNER JOIN FETCH qi.price qsp INNER JOIN FETCH qsp.type"
			+ " WHERE qi.configuration.subscription.id = :subscription")
	List<C> findAll(int subscription);

	/**
	 * Return the resource identifier from the related subscription, and only if this resource can be associated to
	 * network.
	 *
	 * @param subscription The subscription identifier linking the quote.
	 * @return The resource identifiers.
	 */
	@Query("SELECT id FROM #{#entityName} AS qi WHERE qi.configuration.subscription.id = :subscription")
	Set<Integer> findAllNetworkId(int subscription);

	/**
	 * Return the quote item identifier from the related subscription with the related name, and only if this resource
	 * can be associated to network.
	 *
	 * @param subscription The subscription identifier linking the quote.
	 * @return The resource identifiers with its name.
	 */
	@Query("SELECT id, name FROM #{#entityName} AS qi WHERE qi.configuration.subscription.id = :subscription")
	List<Object[]> findAllNetworkIdName(int subscription);

	/**
	 * Return used price codes among the quotes relate to a given node.
	 *
	 * @param node The related node identifier.
	 * @return Used price codes among the quotes relate to a given node.
	 */
	@Query("SELECT DISTINCT p.code FROM #{#entityName} c INNER JOIN c.price p WHERE p.type.node.id = :node")
	Collection<String> finUsedPrices(String node);

}
