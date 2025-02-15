package biz.aQute.resolve;

import static aQute.bnd.osgi.resource.CapReqBuilder.createRequirementFromCapability;
import static aQute.bnd.osgi.resource.ResourceUtils.createWildcardRequirement;
import static aQute.bnd.osgi.resource.ResourceUtils.getIdentityCapability;
import static java.util.Collections.singleton;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.service.repository.Repository;
import org.osgi.service.resolver.ResolutionException;
import org.osgi.service.resolver.Resolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import aQute.bnd.http.HttpClient;
import aQute.bnd.osgi.Processor;
import aQute.bnd.osgi.repository.ResourcesRepository;
import aQute.bnd.osgi.repository.XMLResourceParser;
import aQute.bnd.osgi.resource.FilterParser;
import aQute.bnd.osgi.resource.ResolutionDirective;
import aQute.bnd.osgi.resource.ResourceBuilder;
import aQute.bnd.osgi.resource.ResourceUtils;
import aQute.bnd.osgi.resource.ResourceUtils.IdentityCapability;
import aQute.bnd.repository.osgi.OSGiRepository;
import aQute.lib.collections.MultiMap;
import aQute.lib.justif.Justif;
import aQute.lib.strings.Strings;

public class ResolverValidator extends Processor {
	private final static Logger	logger			= LoggerFactory.getLogger(ResolverValidator.class);

	LogReporter					reporter		= new LogReporter(this);
	Resolver					resolver		= new BndResolver(reporter);
	List<URI>					repositories	= new ArrayList<>();
	Resource					system			= null;

	public enum ResolutionType {
		OK,
		FAIL,
		UNUSED
	}

	public static class Resolution {
		public Resource				resource;
		public ResolutionType		type;
		public String				message;
		public Set<Resource>		resolved	= new LinkedHashSet<>();
		public List<Requirement>	system		= new ArrayList<>();
		public List<Requirement>	repos		= new ArrayList<>();
		public List<Requirement>	missing		= new ArrayList<>();
		public List<Requirement>	optionals	= new ArrayList<>();
		public List<Requirement>	unresolved	= new ArrayList<>();
		@Deprecated
		public boolean				succeeded;
	}

	public ResolverValidator(Processor parent) throws Exception {
		super(parent);
	}

	public ResolverValidator() {}

	public void addRepository(URI url) throws Exception {
		repositories.add(url);
	}

	public void setSystem(Resource resource) throws Exception {
		assert resource != null;
		this.system = resource;
	}

	public List<Resolution> validate() throws Exception {
		try (OSGiRepository repository = new OSGiRepository(); HttpClient httpClient = new HttpClient()) {
			Map<String, String> map = new HashMap<>();
			map.put("locations", Strings.join(repositories));
			map.put("name", "ResolverValidator");
			repository.setProperties(map);
			Processor registry = new Processor();
			registry.addBasicPlugin(httpClient);
			repository.setRegistry(registry);
			repository.setReporter(this);
			Set<Resource> resources = getAllResources(repository);
			return validateResources(repository, resources);
		}
	}

	public List<Resolution> validate(Collection<Resource> toBeChecked) throws Exception {
		Set<Resource> allResources = new LinkedHashSet<>();
		for (URI uri : repositories) {
			allResources.addAll(XMLResourceParser.getResources(uri));
		}
		allResources.addAll(toBeChecked);
		ResourcesRepository repository = new ResourcesRepository(allResources);
		return validateResources(repository, toBeChecked);
	}

	public List<Resolution> validateResources(Repository repository, Collection<Resource> resources) throws Exception {
		setProperty("-runfw", "dummy");
		setProperty("-runprovidedcapabilities.extra", "${native_capability}");
		List<Resolution> result = new ArrayList<>();
		List<Resource> resourceList = new ArrayList<>(resources);

		while (!resourceList.isEmpty()) {
			Resource resource = resourceList.remove(0);
			Resolution resolution = resolve(repository, resource);
			result.add(resolution);
			for (Resource resolved : resolution.resolved) {
				if (resourceList.remove(resolved)) {
					Resolution curResolution = new Resolution();
					curResolution.resource = resolved;
					curResolution.succeeded = true;
					curResolution.type = ResolutionType.OK;
					result.add(curResolution);
				}
			}
		}
		Set<Resource> unused = getAllResources(repository);
		result.forEach(reso -> {
			unused.remove(reso.resource);
			unused.removeAll(reso.resolved);
		});
		unused.forEach(resource -> {
			Resolution r = new Resolution();
			r.resource = resource;
			r.message = "Unused resource";
			r.type = ResolutionType.UNUSED;
			r.succeeded = false;
			result.add(r);
		});

		return result;
	}

	public static Set<Resource> getAllResources(Repository repository) {
		Requirement r = createWildcardRequirement();

		Map<Requirement, Collection<Capability>> providers = repository.findProviders(Collections.singleton(r));
		Set<Resource> resources = ResourceUtils.getResources(providers.get(r));
		return resources;
	}

	private BndrunResolveContext getResolveContext() throws Exception {
		BndrunResolveContext context = new BndrunResolveContext(this, null, this, reporter) {
			@Override
			void loadFramework(ResourceBuilder systemBuilder) throws Exception {
				systemBuilder.addCapabilities(system.getCapabilities(null));
			}
		};
		return context;
	}

	public Requirement getIdentity(Resource resource) {
		IdentityCapability identityCapability = getIdentityCapability(resource);
		return createRequirementFromCapability(identityCapability).buildSyntheticRequirement();
	}

	public Resolution resolve(Repository repository, Resource resource) throws Exception {
		Resolution resolution = new Resolution();

		Requirement identity = getIdentity(resource);
		setProperty("-runrequires", ResourceUtils.toRequireCapability(identity));

		BndrunResolveContext context = getResolveContext();

		context.addRepository(repository);
		context.init();

		resolution.resource = resource;

		try {
			Map<Resource, List<Wire>> resolve2 = resolver.resolve(context);
			resolution.type = ResolutionType.OK;
			resolution.succeeded = true;
			resolution.resolved = resolve2.keySet();

			logger.debug("resolving {} succeeded", resource);
		} catch (ResolutionException e) {
			logger.debug("resolving {} failed", resource);

			resolution.type = ResolutionType.FAIL;
			resolution.succeeded = false;
			resolution.message = e.getMessage();

			for (Requirement req : e.getUnresolvedRequirements()) {
				logger.debug("    missing {}", req);
				resolution.unresolved.add(req);
			}

			ResourcesRepository systemRepository = new ResourcesRepository(system);

			for (Requirement r : resource.getRequirements(null)) {

				Collection<Capability> caps = systemRepository.findProvider(r);

				boolean missing = caps.isEmpty();

				if (missing) {

					Set<Requirement> requirements = singleton(r);
					caps = repository.findProviders(requirements)
						.get(r);
					missing = caps.isEmpty();

					if (missing) {
						if (ResourceUtils.getResolution(r) == ResolutionDirective.optional)
							resolution.optionals.add(r);
						else
							resolution.missing.add(r);

					} else {
						logger.debug("     found {} in repo", r);
						resolution.repos.add(r);
					}
				} else {
					logger.debug("     found {} in system", r);
					resolution.system.add(r);
				}
			}

			error("%s", ResolveProcess.format(e, false));
		} catch (Exception e) {
			e.printStackTrace();
			error("resolving %s failed with %s", context.getInputResource()
				.getRequirements(null), e);
			resolution.message = e.getMessage();
		}

		return resolution;
	}

	/**
	 * Validate all resources in the repository against the repository and the
	 * system resource as setup.
	 *
	 * @param repository the repository providing the resources & used to
	 *            resolve against
	 * @return a list of resolutions, one per resource
	 */
	public List<Resolution> validateResources(Repository repository) throws Exception {
		return validateResources(repository, getAllResources(repository));
	}

	/**
	 * Printout a cross reference of the results of missing requirements ->
	 * using resources.
	 *
	 * @param result the resolutions with missing requirements
	 * @return a formatted report string
	 */
	public String xrefMissing(List<Resolution> result) {
		MultiMap<Requirement, Resolution> grouped = new MultiMap<>();
		result.forEach(reso -> reso.missing.forEach(req -> grouped.add(req, reso)));

		TreeSet<Requirement> ts = new TreeSet<Requirement>(ResourceUtils.REQUIREMENT_COMPARATOR);
		ts.addAll(grouped.keySet());

		Justif j = new Justif(300, 20, 70, 90, 120);
		String ns = null;
		String del = "";
		for (Requirement requirement : ts) {

			if (!requirement.getNamespace()
				.equals(ns)) {
				ns = requirement.getNamespace();
				if (ns.matches("osgi.unresolvable|osgi.compile.time.only"))
					continue;
				j.formatter()
					.format("%s%-20s", del, ns);
				String nicer = FilterParser.namespaceToCategory(ns);
				if (!nicer.equals(ns)) {
					j.formatter()
						.format("\t2%s\n", nicer);
				} else
					j.formatter()
						.format("\n");
				del = "\n";
			}

			String filter = FilterParser.toString(requirement, false);
			TreeSet<Resource> resources = new TreeSet<>(ResourceUtils.IDENTITY_VERSION_COMPARATOR);
			grouped.get(requirement)
				.forEach(resolution -> resources.add(resolution.resource));
			j.entry("   " + filter, " ", resources);
		}

		return j.wrap();
	}

	public String unused(List<Resolution> result) {
		return result.stream()
			.filter(r -> r.type == ResolutionType.UNUSED)
			.map(r -> r.resource.toString())
			.collect(Collectors.joining("\n"));
	}
}
