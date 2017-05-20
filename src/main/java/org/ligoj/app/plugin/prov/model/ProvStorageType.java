package org.ligoj.app.plugin.prov.model;

import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.validation.constraints.NotNull;

import org.ligoj.app.api.NodeScoped;
import org.ligoj.app.model.Node;
import org.ligoj.bootstrap.core.model.AbstractDescribedEntity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

/**
 * Storage type of a provider.
 */
@Getter
@Setter
@Entity
@Table(name = "LIGOJ_PROV_STORAGE_TYPE", uniqueConstraints = @UniqueConstraint(columnNames = { "name", "node" }))
public class ProvStorageType extends AbstractDescribedEntity<Integer> implements NodeScoped {

	/**
	 * SID
	 */
	private static final long serialVersionUID = 4795855466011388616L;

	/**
	 * The monthly cost of 1Go (Giga Bytes).
	 */
	@NotNull
	private Double cost;

	/**
	 * The frequency access
	 */
	@NotNull
	private ProvStorageFrequency frequency;
	
	/**
	 * Optimized best usage of this storage
	 */
	private ProvStorageOptimized optimized;

	/**
	 * The minimal disk in "Go".
	 */
	private int minimal = 0;

	/**
	 * <code>true</code> when this storage can attached to an instance.
	 */
	private boolean instanceCompatible = false;

	/**
	 * The maximum supported size in "Go". May be <code>null</code>.
	 */
	private Integer maximal;

	/**
	 * The cost per transaction. May be <code>0</code>.
	 */
	private double transactionalCost;

	/**
	 * The enabled provider.
	 */
	@NotNull
	@ManyToOne(fetch = FetchType.LAZY)
	@JsonIgnore
	private Node node;

}