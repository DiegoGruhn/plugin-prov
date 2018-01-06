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
 * An instance price term configuration
 */
@Getter
@Setter
@Entity
@Table(name = "LIGOJ_PROV_INSTANCE_PRICE_TERM", uniqueConstraints = @UniqueConstraint(columnNames = { "name", "node" }))
public class ProvInstancePriceTerm extends AbstractDescribedEntity<Integer> implements NodeScoped {

	/**
	 * Billing period duration in minutes. Any started period is due.
	 */
	@NotNull
	private Integer period;

	/**
	 * Minimal billing period in minutes. Any started period is due for this
	 * minimum. When <code>null</code>, corresponds to the period. <br>
	 * When <code>usage &lt; minimum &lt; period</code> then nothing is billed.<br>
	 * When <code>minimum &lt; usage &lt; period</code> then <code>period</code> is
	 * billed. <br>
	 * When <code>period1 &lt; usage &lt; period2</code> then <code>period2</code>
	 * is billed.
	 */
	private Integer minimum;

	/**
	 * The related node (VM provider) of this instance.
	 */
	@NotNull
	@ManyToOne(fetch = FetchType.LAZY)
	@JsonIgnore
	private Node node;

	/**
	 * The price may vary within the period.
	 */
	private boolean variable;

	/**
	 * The instance could be terminated by the provider.
	 */
	private boolean ephemeral;

	/**
	 * The internal offer code.
	 */
	@NotNull
	private String code;
}