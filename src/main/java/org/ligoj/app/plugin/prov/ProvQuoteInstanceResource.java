
package org.ligoj.app.plugin.prov;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import javax.transaction.Transactional;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.cxf.jaxrs.ext.multipart.Multipart;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceTermRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstanceTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteInstanceRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteStorageRepository;
import org.ligoj.app.plugin.prov.dao.ProvUsageRepository;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvQuote;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvStorageLatency;
import org.ligoj.app.plugin.prov.model.ProvUsage;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.bootstrap.core.DescribedBean;
import org.ligoj.bootstrap.core.INamableBean;
import org.ligoj.bootstrap.core.csv.CsvForBean;
import org.ligoj.bootstrap.core.json.TableItem;
import org.ligoj.bootstrap.core.json.datatable.DataTableAttributes;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * The instance part of the provisioning.
 */
@Service
@Path(ProvResource.SERVICE_URL)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
@Slf4j
public class ProvQuoteInstanceResource extends AbstractCostedResource<ProvQuoteInstance> {
	private static final String[] DEFAULT_COLUMNS = { "name", "cpu", "ram", "os", "disk", "latency", "optimized" };
	private static final String[] ACCEPTED_COLUMNS = { "name", "cpu", "ram", "constant", "os", "disk", "latency", "optimized", "term",
			"type", "internet", "maxCost", "minQuantity", "maxQuantity", "maxVariableCost", "ephemeral", "location", "usage" };

	@Autowired
	private ProvQuoteStorageResource storageResource;

	@Autowired
	private CsvForBean csvForBean;

	@Autowired
	private ProvInstancePriceRepository ipRepository;

	@Autowired
	private ProvInstancePriceTermRepository iptRepository;

	@Autowired
	private ProvQuoteInstanceRepository qiRepository;

	@Autowired
	private ProvInstanceTypeRepository itRepository;

	@Autowired
	private ProvQuoteStorageRepository qsRepository;
	@Autowired
	private ProvUsageRepository usageRepository;

	/**
	 * Create the instance inside a quote.
	 * 
	 * @param vo
	 *            The quote instance.
	 * @return The created instance cost details with identifier.
	 */
	@POST
	@Path("instance")
	@Consumes(MediaType.APPLICATION_JSON)
	public UpdatedCost create(final QuoteInstanceEditionVo vo) {
		return saveOrUpdate(new ProvQuoteInstance(), vo);
	}

	/**
	 * Update the instance inside a quote.
	 * 
	 * @param vo
	 *            The quote instance to update.
	 * @return The new cost configuration.
	 */
	@PUT
	@Path("instance")
	@Consumes(MediaType.APPLICATION_JSON)
	public UpdatedCost update(final QuoteInstanceEditionVo vo) {
		return saveOrUpdate(resource.findConfigured(qiRepository, vo.getId()), vo);
	}

	/**
	 * Save or update the given entity from the {@link QuoteInstanceEditionVo}. The
	 * computed cost are recursively updated from the instance to the quote total
	 * cost.
	 */
	private UpdatedCost saveOrUpdate(final ProvQuoteInstance entity, final QuoteInstanceEditionVo vo) {
		// Compute the unbound cost delta
		final int deltaUnbound = BooleanUtils.toInteger(vo.getMaxQuantity() == null) - BooleanUtils.toInteger(entity.isUnboundCost());

		// Check the associations and copy attributes to the entity
		final ProvQuote configuration = getQuoteFromSubscription(vo.getSubscription());
		entity.setConfiguration(configuration);
		final Subscription subscription = configuration.getSubscription();
		final String providerId = subscription.getNode().getRefined().getId();
		DescribedBean.copy(vo, entity);
		entity.setPrice(ipRepository.findOneExpected(vo.getPrice()));
		entity.setLocation(resource.findLocation(subscription.getId(), vo.getLocation()));
		entity.setUsage(Optional.ofNullable(vo.getUsage()).map(u -> resource.findConfigured(usageRepository, u)).orElse(null));
		entity.setOs(ObjectUtils.defaultIfNull(vo.getOs(), entity.getPrice().getOs()));
		entity.setRam(vo.getRam());
		entity.setCpu(vo.getCpu());
		entity.setConstant(vo.getConstant());
		entity.setEphemeral(vo.isEphemeral());
		entity.setInternet(vo.getInternet());
		entity.setMaxVariableCost(vo.getMaxVariableCost());
		entity.setMinQuantity(vo.getMinQuantity());
		entity.setMaxQuantity(vo.getMaxQuantity());

		resource.checkVisibility(entity.getPrice().getType(), providerId);
		checkConstraints(entity);
		checkOs(entity);

		// Update the unbound increment of the global quote
		configuration.setUnboundCostCounter(configuration.getUnboundCostCounter() + deltaUnbound);

		// Save and update the costs
		final UpdatedCost cost = newUpdateCost(entity);
		final Map<Integer, FloatingCost> storagesCosts = new HashMap<>();
		CollectionUtils.emptyIfNull(entity.getStorages())
				.forEach(s -> storagesCosts.put(s.getId(), addCost(s, storageResource::updateCost)));
		cost.setRelatedCosts(storagesCosts);
		cost.setTotalCost(toFloatingCost(entity.getConfiguration()));
		return cost;
	}

	/**
	 * Check the requested OS is compliant with the one of associated
	 * {@link ProvInstancePrice}
	 */
	private void checkOs(ProvQuoteInstance entity) {
		if (entity.getOs().toPricingOs() != entity.getPrice().getOs()) {
			// Incompatible, hack attempt?
			log.warn("Attempt to create an instance with an incompatible OS {} with catalog OS {}", entity.getOs(),
					entity.getPrice().getOs());
			throw new ValidationJsonException("os", "incompatible-os", entity.getPrice().getOs());
		}
	}

	private void checkConstraints(ProvQuoteInstance entity) {
		if (entity.getMaxQuantity() != null && entity.getMaxQuantity() < entity.getMinQuantity()) {
			// Maximal quantity must be greater than minimal quantity
			throw new ValidationJsonException("maxQuantity", "Min", entity.getMinQuantity());
		}
	}

	/**
	 * Delete all instances from a quote. The total cost is updated.
	 * 
	 * @param subscription
	 *            The related subscription.
	 * @return The updated computed cost.
	 */
	@DELETE
	@Path("{subscription:\\d+}/instance")
	@Consumes(MediaType.APPLICATION_JSON)
	public FloatingCost deleteAll(@PathParam("subscription") final int subscription) {
		subscriptionResource.checkVisibleSubscription(subscription);

		// Delete all instance with cascaded delete for storages
		qiRepository.deleteAll(qiRepository.findAllBy("configuration.subscription.id", subscription));

		// Update the cost. Note the effort could be reduced to a simple
		// subtract of instances cost and related storage costs
		return resource.refreshCost(subscription);
	}

	@Override
	public FloatingCost refresh(final ProvQuoteInstance instance) {
		final ProvQuote quote = instance.getConfiguration();

		// Find the lowest price
		final ProvInstancePrice price = validateLookup("instance",
				lookup(quote, instance.getCpu(), instance.getRam(), instance.getConstant(), instance.getOs(), null,
						instance.getPrice().getTerm().getName(), instance.isEphemeral(),
						Optional.ofNullable(instance.getLocation()).map(INamableBean::getName).orElse(null), getUsageName(instance)),
				instance.getName());
		instance.setPrice(price);
		return updateCost(instance);
	}

	/**
	 * Return the usage applied to the given instance. May be <code>null</code>.
	 */
	private ProvUsage getUsage(final ProvQuoteInstance instance) {
		return instance.getUsage() == null ? instance.getConfiguration().getUsage() : instance.getUsage();
	}

	/**
	 * Return the usage name applied to the given instance. May be
	 * <code>null</code>.
	 */
	private String getUsageName(final ProvQuoteInstance instance) {
		final ProvUsage usage = getUsage(instance);
		return usage == null ? null : usage.getName();
	}

	/**
	 * Delete an instance from a quote. The total cost is updated.
	 * 
	 * @param id
	 *            The {@link ProvQuoteInstance}'s identifier to delete.
	 * @return The updated computed cost.
	 */
	@DELETE
	@Path("instance/{id:\\d+}")
	@Consumes(MediaType.APPLICATION_JSON)
	public FloatingCost delete(@PathParam("id") final int id) {
		// Delete the instance and also the attached storage
		return deleteAndUpdateCost(qiRepository, id, i -> {
			// Delete the relate storages
			i.getStorages().forEach(s -> deleteAndUpdateCost(qsRepository, s.getId(), Function.identity()::apply));

			// Decrement the unbound counter
			final ProvQuote configuration = i.getConfiguration();
			configuration.setUnboundCostCounter(configuration.getUnboundCostCounter() - BooleanUtils.toInteger(i.isUnboundCost()));
		});
	}

	/**
	 * Create the instance inside a quote.
	 * 
	 * @param subscription
	 *            The subscription identifier, will be used to filter the instances
	 *            from the associated provider.
	 * @param cpu
	 *            The amount of required CPU. Default is 1.
	 * @param ram
	 *            The amount of required RAM, in MB. Default is 1.
	 * @param constant
	 *            Optional constant CPU. When <code>false</code>, variable CPU is
	 *            requested. When <code>true</code> constant CPU is requested.
	 * @param os
	 *            The requested OS, default is "LINUX".
	 * @param type
	 *            Optional instance type name. May be <code>null</code>.
	 * @param term
	 *            Optional price term name. May be <code>null</code>.
	 * @param ephemeral
	 *            Optional ephemeral constraint. When <code>false</code> (default),
	 *            only non ephemeral instance are accepted. Otherwise
	 *            (<code>true</code>), ephemeral instance contract is accepted.
	 * @param location
	 *            Optional location name. May be <code>null</code>.
	 * @param usage
	 *            Optional usage name. May be <code>null</code>.
	 * @return The lowest price instance configurations matching to the required
	 *         parameters. May be a template or a custom instance type.
	 */
	@GET
	@Path("{subscription:\\d+}/instance-lookup")
	@Consumes(MediaType.APPLICATION_JSON)
	public QuoteInstanceLookup lookup(@PathParam("subscription") final int subscription,
			@DefaultValue(value = "1") @QueryParam("cpu") final double cpu, @DefaultValue(value = "1") @QueryParam("ram") final int ram,
			@QueryParam("constant") final Boolean constant, @DefaultValue(value = "LINUX") @QueryParam("os") final VmOs os,
			@QueryParam("type") final String type, @QueryParam("term") final String term, @QueryParam("ephemeral") final boolean ephemeral,
			@QueryParam("location") final String location, @QueryParam("usage") final String usage) {
		// Check the security on this subscription
		return lookup(getQuoteFromSubscription(subscription), cpu, ram, constant, os, type, term, ephemeral, location, usage);
	}

	private QuoteInstanceLookup lookup(final ProvQuote configuration, final double cpu, final int ram, final Boolean constant,
			final VmOs osName, final String type, final String term, final boolean ephemeral, final String location, final String usage) {
		final String node = configuration.getSubscription().getNode().getId();
		final int subscription = configuration.getSubscription().getId();

		// Resolve
		final VmOs os = Optional.ofNullable(osName).map(VmOs::toPricingOs).orElse(null);

		// Resolve the location to use
		final String locationR = location == null ? configuration.getLocation().getName() : location;

		// Compute the rate to use
		final int rate = getRate(configuration, usage == null ? null : resource.findConfigured(usageRepository, usage));

		// Resolve the required instance type
		final Integer typeId = type == null ? null : assertFound(itRepository.findByName(subscription, type), type).getId();

		// Resolve the required term
		final Integer termId = term == null ? null : assertFound(iptRepository.findByName(subscription, term), term).getId();

		// Return only the first matching instance
		// Template instance
		final QuoteInstanceLookup template = ipRepository
				.findLowestPrice(node, cpu, ram, constant, os, termId, typeId, ephemeral, locationR, rate, PageRequest.of(0, 1)).stream()
				.findFirst().map(ip -> newPrice((ProvInstancePrice) ip[0], toMonthly((double) ip[1], 10))).orElse(null);

		// Custom instance
		final QuoteInstanceLookup custom = ipRepository.findLowestCustomPrice(node, constant, os, termId, locationR, PageRequest.of(0, 1))
				.stream().findFirst().map(ip -> newPrice(ip, toMonthly(getCustomCost(cpu, ram, ip)))).orElse(null);

		// Select the best instance
		if (template == null) {
			return custom;
		}
		if (custom == null) {
			return template;
		}
		return custom.getCost() < template.getCost() ? custom : template;
	}

	/**
	 * Return the instance price type available for a subscription.
	 * 
	 * @param subscription
	 *            The subscription identifier, will be used to filter the instances
	 *            from the associated provider.
	 * @param uriInfo
	 *            filter data.
	 * @return The available price types for the given subscription.
	 */
	@GET
	@Path("{subscription:\\d+}/instance-price-term")
	@Consumes(MediaType.APPLICATION_JSON)
	public TableItem<ProvInstancePriceTerm> findPriceTerm(@PathParam("subscription") final int subscription,
			@Context final UriInfo uriInfo) {
		subscriptionResource.checkVisibleSubscription(subscription);
		return paginationJson.applyPagination(uriInfo, iptRepository.findAll(subscription, DataTableAttributes.getSearch(uriInfo),
				paginationJson.getPageRequest(uriInfo, ProvResource.ORM_COLUMNS)), Function.identity());
	}

	/**
	 * Return the instance types inside a quote.
	 * 
	 * @param subscription
	 *            The subscription identifier, will be used to filter the instances
	 *            from the associated provider.
	 * @param uriInfo
	 *            filter data.
	 * @return The valid instance types for the given subscription.
	 */
	@GET
	@Path("{subscription:\\d+}/instance")
	@Consumes(MediaType.APPLICATION_JSON)
	public TableItem<ProvInstanceType> findAll(@PathParam("subscription") final int subscription, @Context final UriInfo uriInfo) {
		subscriptionResource.checkVisibleSubscription(subscription);
		return paginationJson.applyPagination(uriInfo, itRepository.findAll(subscription, DataTableAttributes.getSearch(uriInfo),
				paginationJson.getPageRequest(uriInfo, ProvResource.ORM_COLUMNS)), Function.identity());
	}

	/**
	 * Build a new {@link QuoteInstanceLookup} from {@link ProvInstancePrice} and
	 * computed price.
	 */
	private QuoteInstanceLookup newPrice(final ProvInstancePrice ip, final double cost) {
		final QuoteInstanceLookup result = new QuoteInstanceLookup();
		result.setCost(cost);
		result.setPrice(ip);
		return result;
	}

	@Override
	public FloatingCost updateCost(final ProvQuoteInstance qi) {
		return updateCost(qi, this::getCost, this::toMonthly);
	}

	/**
	 * Compute the hourly cost of a quote instance.
	 * 
	 * @param qi
	 *            The quote to evaluate.
	 * @return The cost of this instance.
	 */
	private FloatingCost getCost(final ProvQuoteInstance qi) {
		// Fixed price + custom price
		final ProvInstancePrice ip = qi.getPrice();
		final double rate;
		if (ip.getTerm().getPeriod() > 60) {
			// Related term has a period greater than the hour, ignore usage
			rate = 1d;
		} else {
			rate = getRate(qi) / 100d;
		}
		return computeFloat(rate * (ip.getCost() + (ip.getType().isCustom() ? getCustomCost(qi.getCpu(), qi.getRam(), ip) : 0)), qi);
	}

	private int getRate(final ProvQuoteInstance qi) {
		return Optional.ofNullable(getUsage(qi)).map(ProvUsage::getRate).orElse(100);
	}

	private int getRate(final ProvQuote quote, final ProvUsage usage) {
		return Optional.ofNullable(Optional.ofNullable(usage).orElse(quote.getUsage())).map(ProvUsage::getRate).orElse(100);
	}

	/**
	 * Compute the hourly cost of a custom requested resource.
	 * 
	 * @param cpu
	 *            The requested CPU.
	 * @param ram
	 *            The requested RAM.
	 * @param ip
	 *            The instance price configuration.
	 * @return The cost of this custom instance.
	 */
	private double getCustomCost(final Double cpu, final Integer ram, final ProvInstancePrice ip) {
		// Compute the count of the requested resources
		return getCustomCost(cpu, ip.getCostCpu(), 1) + getCustomCost(ram, ip.getCostRam(), 1024);
	}

	/**
	 * Compute the hourly cost of a custom requested resource.
	 * 
	 * @param requested
	 *            The request resource amount.
	 * @param cost
	 *            The cost of one resource.
	 * @param weight
	 *            The weight of one resource.
	 * @return The cost of this custom instance.
	 */
	private double getCustomCost(Number requested, Double cost, final double weight) {
		// Compute the count of the requested resources
		return Math.ceil(requested.doubleValue() / weight) * cost;
	}

	/**
	 * Compute the cost using minimal and maximal quantity of related instance. no
	 * rounding there.
	 */
	public FloatingCost computeFloat(final double base, final ProvQuoteInstance instance) {
		final FloatingCost cost = new FloatingCost();
		cost.setMin(base * instance.getMinQuantity());
		cost.setMax(Optional.ofNullable(instance.getMaxQuantity()).orElse(instance.getMinQuantity()) * base);
		cost.setUnbound(instance.isUnboundCost());
		return cost;
	}

	/**
	 * Check column's name validity
	 */
	private void checkHeaders(final String[] expected, final String... columns) {
		for (final String column : columns) {
			if (!ArrayUtils.contains(expected, column.trim())) {
				throw new BusinessException("Invalid header", column);
			}
		}
	}

	/**
	 * Upload a file of quote in add mode.
	 * 
	 * @param subscription
	 *            The subscription identifier, will be used to filter the locations
	 *            from the associated provider.
	 * @param uploadedFile
	 *            Instance entries files to import. Currently support only CSV
	 *            format.
	 * @param columns
	 *            the CSV header names.
	 * @param ramMultiplier
	 *            The multiplier for imported RAM values. Default is 1.
	 * @param term
	 *            The default {@link ProvInstancePriceTerm} used when no one is
	 *            defined in the CSV line
	 * @param encoding
	 *            CSV encoding. Default is UTF-8.
	 */
	@POST
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	@Path("{subscription:\\d+}/upload")
	public void upload(@PathParam("subscription") final int subscription, @Multipart(value = "csv-file") final InputStream uploadedFile,
			@Multipart(value = "columns", required = false) final String[] columns,
			@Multipart(value = "term", required = false) final String term,
			@Multipart(value = "memoryUnit", required = false) final Integer ramMultiplier,
			@Multipart(value = "encoding", required = false) final String encoding) throws IOException {
		subscriptionResource.checkVisibleSubscription(subscription).getNode().getId();

		// Check column's name validity
		final String[] sanitizeColumns = ArrayUtils.isEmpty(columns) ? DEFAULT_COLUMNS : columns;
		checkHeaders(ACCEPTED_COLUMNS, sanitizeColumns);

		// Build CSV header from array
		final String csvHeaders = StringUtils.chop(ArrayUtils.toString(sanitizeColumns)).substring(1).replace(',', ';') + "\n";

		// Build entries
		final String safeEncoding = ObjectUtils.defaultIfNull(encoding, StandardCharsets.UTF_8.name());
		csvForBean
				.toBean(InstanceUpload.class, new InputStreamReader(
						new SequenceInputStream(new ByteArrayInputStream(csvHeaders.getBytes(safeEncoding)), uploadedFile), safeEncoding))
				.stream().filter(Objects::nonNull).forEach(i -> persist(i, subscription, term, ramMultiplier));
	}

	private void persist(final InstanceUpload upload, final int subscription, final String defaultTerm, final Integer ramMultiplier) {
		final QuoteInstanceEditionVo vo = new QuoteInstanceEditionVo();
		vo.setCpu(round(ObjectUtils.defaultIfNull(upload.getCpu(), 0d)));
		vo.setEphemeral(upload.isEphemeral());
		vo.setInternet(upload.getInternet());
		vo.setMaxVariableCost(upload.getMaxVariableCost());
		vo.setMaxQuantity(Optional.ofNullable(upload.getMaxQuantity()).map(q -> q <= 0 ? null : q).orElse(null));
		vo.setMinQuantity(upload.getMinQuantity());
		vo.setName(upload.getName());
		vo.setLocation(upload.getLocation());
		vo.setUsage(upload.getUsage() == null ? null : resource.findConfigured(usageRepository, upload.getUsage()).getName());
		vo.setRam(ObjectUtils.defaultIfNull(ramMultiplier, 1) * ObjectUtils.defaultIfNull(upload.getRam(), 0).intValue());
		vo.setSubscription(subscription);

		// Choose the required term
		final String term = upload.getTerm() == null ? defaultTerm : upload.getTerm();

		// Find the lowest price
		final ProvInstancePrice instancePrice = validateLookup("instance",
				lookup(subscription, vo.getCpu(), vo.getRam(), upload.getConstant(), upload.getOs(), upload.getType(), term,
						upload.isEphemeral(), upload.getLocation(), upload.getUsage()),
				upload.getName());

		vo.setPrice(instancePrice.getId());
		final UpdatedCost newInstance = create(vo);
		final int qi = newInstance.getId();

		// Storage part
		final Integer size = Optional.ofNullable(upload.getDisk()).map(Double::intValue).orElse(0);
		if (size > 0) {
			// Size is provided
			final QuoteStorageEditionVo svo = new QuoteStorageEditionVo();

			// Default the storage latency to HOT when not specified
			final ProvStorageLatency latency = ObjectUtils.defaultIfNull(upload.getLatency(), ProvStorageLatency.LOW);

			// Find the nicest storage
			svo.setType(storageResource.lookup(subscription, size, latency, qi, upload.getOptimized(), upload.getLocation()).stream()
					.findFirst().orElseThrow(() -> new ValidationJsonException("storage", "NotNull")).getPrice().getType().getName());

			// Default the storage name to the instance name
			svo.setName(vo.getName());
			svo.setQuoteInstance(qi);
			svo.setSize(size);
			svo.setSubscription(subscription);
			storageResource.create(svo);
		}

	}

	/**
	 * Request a cost update of the given entity and report the delta to the the
	 * global cost. The changes are persisted.
	 */
	public UpdatedCost newUpdateCost(final ProvQuoteInstance entity) {
		return newUpdateCost(qiRepository, entity, this::updateCost);
	}

}