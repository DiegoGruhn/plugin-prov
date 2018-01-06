package org.ligoj.app.plugin.prov.model;

import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;

/**
 * Storage price for a storage type.<br>
 * The meaning to the cost attribute is the monthly cost of 1GiB (1024 MiB).
 */
@Getter
@Setter
@Entity
@Table(name = "LIGOJ_PROV_STORAGE_PRICE", uniqueConstraints = @UniqueConstraint(columnNames = { "type", "location" }))
public class ProvStoragePrice extends AbstractPrice<ProvStorageType> {

	/**
	 * The monthly cost of 1GiB (Gibibyte Bytes).
	 * @see https://en.wikipedia.org/wiki/Gibibyte
	 */
	private double costGb = 0;

	/**
	 * The cost per transaction. May be <code>0</code>.
	 */
	private double costTransaction;
}