package au.edu.wehi.idsv;

import htsjdk.samtools.SamPairUtil.PairOrientation;
import htsjdk.samtools.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import picard.analysis.InsertSizeMetrics;
import picard.cmdline.CommandLineProgramProperties;
import picard.cmdline.Option;
import au.edu.wehi.idsv.pipeline.SortRealignedSoftClips;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Extracts structural variation evidence and assembles breakends
 * @author Daniel Cameron
 *
 */
@CommandLineProgramProperties(
        usage = "Calls structural variations from one or more SAM/BAM input files.",  
        usageShort = "Calls structural variations from NGS sequencing data"
)
public class Idsv extends CommandLineProgram {
	private Log log = Log.getInstance(Idsv.class);
	@Option(doc="Number of worker threads to spawn."
			+ " Note that I/O threads not included in this worker thread count so CPU usage can be higher than the number of worker thread.",
    		shortName="THREADS")
    public int WORKER_THREADS = Runtime.getRuntime().availableProcessors();
    // The following attributes define the command-line arguments
	@Option(doc="VCF containing true calls. Called variants will be annotated as true/false positive calls based on this truth file.", optional=true)
    public File TRUTH_VCF = null;
    @Option(doc = "Processing steps to execute",
            optional = true)
    public EnumSet<ProcessStep> STEPS = ProcessStep.ALL_STEPS;
    private SAMEvidenceSource constructSamEvidenceSource(File file, int index, int category) throws IOException { 
    	List<Integer> maxFragSizeList = category == 1 ? INPUT_TUMOUR_READ_PAIR_MAX_CONCORDANT_FRAGMENT_SIZE : INPUT_READ_PAIR_MAX_CONCORDANT_FRAGMENT_SIZE;
    	List<Integer> minFragSizeList = category == 1 ? INPUT_TUMOUR_READ_PAIR_MIN_CONCORDANT_FRAGMENT_SIZE : INPUT_READ_PAIR_MIN_CONCORDANT_FRAGMENT_SIZE;
    	int maxFragSize = 0;
    	int minFragSize = 0;
    	if (maxFragSizeList != null && maxFragSizeList.size() > index && maxFragSizeList.get(index) != null) {
    		maxFragSize = maxFragSizeList.get(index);
    	}
    	if (minFragSizeList != null && minFragSizeList.size() > index && minFragSizeList.get(index) != null) {
    		minFragSize = minFragSizeList.get(index);
    	}
    	if (maxFragSize > 0) {
    		return new SAMEvidenceSource(getContext(), file, category, minFragSize, maxFragSize);
    	} else if (READ_PAIR_CONCORDANT_PERCENT != null) {
    		return new SAMEvidenceSource(getContext(), file, category, READ_PAIR_CONCORDANT_PERCENT);
    	} else {
    		return new SAMEvidenceSource(getContext(), file, category);
    	}
    }
    public List<SAMEvidenceSource> createSamEvidenceSources() throws IOException {
    	int i = 0;
    	List<SAMEvidenceSource> samEvidence = Lists.newArrayList();
    	for (File f : INPUT) {
    		log.info("Processing normal input ", f);
    		SAMEvidenceSource sref = constructSamEvidenceSource(f, i++, 0);
    		samEvidence.add(sref);
    	}
    	for (File f : INPUT_TUMOUR) {
    		log.info("Processing tumour input ", f);
    		SAMEvidenceSource sref = constructSamEvidenceSource(f, i++, 1);
    		samEvidence.add(sref);
    	}
    	return samEvidence;
    }
    @Override
	protected int doWork() {
    	ExecutorService threadpool = null;
    	ExecutorService halfthreadpool = null;
    	try {
    		hackSimpleCalls();
    		threadpool = Executors.newFixedThreadPool(WORKER_THREADS, new ThreadFactoryBuilder().setDaemon(false).setNameFormat("Worker-%d").build());
    		halfthreadpool = Executors.newFixedThreadPool((int)Math.ceil(WORKER_THREADS/2.0), new ThreadFactoryBuilder().setDaemon(false).setNameFormat("HalfWorker-%d").build());
    		log.info(String.format("Using %d worker threads", WORKER_THREADS));
	    	ensureDictionariesMatch();
	    	List<SAMEvidenceSource> samEvidence = createSamEvidenceSources();
	    	
	    	extractEvidence(threadpool, samEvidence);
	    	
	    	for (SAMEvidenceSource e : samEvidence) {
	    		InsertSizeMetrics ism = e.getMetrics().getInsertSizeMetrics();
	    		if (ism != null && ism.PAIR_ORIENTATION != PairOrientation.FR) {
	    			log.error("gridss currently supports only FR read pair orientation. If usage with other read pair orientations is required, please raise an issue at https://github.com/PapenfussLab/gridss/issues");
	    			return -1;
	    		}
	    	}
	    	
	    	if (!checkRealignment(samEvidence, WORKER_THREADS)) {
	    		return -1;
    		}
	    	sortRealignedSoftClips(threadpool, samEvidence);
	    	
	    	AssemblyEvidenceSource assemblyEvidence = new AssemblyEvidenceSource(getContext(), samEvidence, OUTPUT);
	    	assemblyEvidence.ensureAssembled(threadpool, halfthreadpool);
	    	
	    	List<EvidenceSource> allEvidence = Lists.newArrayList();
	    	allEvidence.add(assemblyEvidence);
	    	allEvidence.addAll(samEvidence);
	    	
	    	if (!checkRealignment(ImmutableList.of(assemblyEvidence), WORKER_THREADS)) {
	    		return -1;
    		}
			
	    	// check that all steps have been completed
	    	for (SAMEvidenceSource sref : samEvidence) {
	    		if (!sref.isComplete(ProcessStep.CALCULATE_METRICS)
	    			|| !sref.isComplete(ProcessStep.EXTRACT_SOFT_CLIPS)
	    			|| !sref.isComplete(ProcessStep.EXTRACT_READ_PAIRS)
	    			|| !sref.isComplete(ProcessStep.REALIGN_SOFT_CLIPS)
	    			|| !sref.isComplete(ProcessStep.SORT_REALIGNED_SOFT_CLIPS)) {
	    			log.error("Unable to call variants: processing and realignment for ", sref.getSourceFile(), " is not complete.");
	    			return -1;
	    		}
	    	}
	    	
	    	compoundRealignment(threadpool, ImmutableList.of(assemblyEvidence));
	    	if (!assemblyEvidence.isRealignmentComplete()) {
	    		log.error("Unable to call variants: generation and breakend alignment of assemblies not complete.");
    			return -1;
	    	}
	    	
	    	shutdownPool(threadpool);
	    	shutdownPool(halfthreadpool);
			threadpool = null;
			halfthreadpool = null;
			
	    	if (!OUTPUT.exists()) {
		    	callVariants(threadpool, samEvidence, assemblyEvidence);
	    	} else {
	    		log.info(OUTPUT.toString() + " exists not recreating.");
	    	}
	    	hackSimpleCalls();
    	} catch (IOException e) {
    		log.error(e);
    		throw new RuntimeException(e);
    	} catch (InterruptedException e) {
    		log.error("Interrupted waiting for task to complete", e);
    		throw new RuntimeException(e);
		} catch (ExecutionException e) {
			log.error("Exception thrown from background task", e.getCause());
    		throw new RuntimeException("Exception thrown from background task", e.getCause());
		} finally {
			shutdownPool(threadpool);
			threadpool = null;
		}
		return 0;
    }
	private void sortRealignedSoftClips(ExecutorService threadpool, List<SAMEvidenceSource> samEvidence) throws InterruptedException, ExecutionException {
		for (Future<Void> future : threadpool.invokeAll(Lists.transform(samEvidence, new Function<SAMEvidenceSource, Callable<Void>>() {
			@Override
			public Callable<Void> apply(final SAMEvidenceSource input) {
				return new Callable<Void>() {
					@Override
					public Void call() throws Exception {
						try {
							SortRealignedSoftClips sortSplitReads = new SortRealignedSoftClips(getContext(), input);
				    		if (!sortSplitReads.isComplete()) {
				    			sortSplitReads.process(STEPS);
				    		}
				    		sortSplitReads.close();
						} catch (Exception e) {
							log.error(e, "Exception thrown by worker thread");
							throw e;
						}
						return null;
					}
				};}}))) {
			// throw exception from worker thread here
			future.get();
		}
	}
	private void extractEvidence(ExecutorService threadpool, List<SAMEvidenceSource> samEvidence) throws InterruptedException, ExecutionException {
		log.info("Extracting evidence.");
		for (Future<Void> future : threadpool.invokeAll(Lists.transform(samEvidence, new Function<SAMEvidenceSource, Callable<Void>>() {
				@Override
				public Callable<Void> apply(final SAMEvidenceSource input) {
					return new Callable<Void>() {
						@Override
						public Void call() throws Exception {
							try {
								input.completeSteps(EnumSet.of(ProcessStep.CALCULATE_METRICS, ProcessStep.EXTRACT_SOFT_CLIPS, ProcessStep.EXTRACT_READ_PAIRS));
							} catch (Exception e) {
								log.error(e, "Exception thrown by worker thread");
								throw e;
							}
							return null;
						}
					};
				}
			}))) {
			// throw exception from worker thread here
			future.get();
		}
		log.info("Evidence extraction complete.");
	}
	private void callVariants(ExecutorService threadpool, List<SAMEvidenceSource> samEvidence, AssemblyEvidenceSource assemblyEvidence) throws IOException {
		VariantCaller caller = null;
		try {
			EvidenceToCsv evidenceDump = null;
			if (Defaults.EXPORT_EVIDENCE_ALLOCATION) {
				evidenceDump = new EvidenceToCsv(new File(getContext().getFileSystemContext().getIntermediateDirectory(OUTPUT), "evidence.csv"));
			}
			// Run variant caller single-threaded as we can do streaming calls
			caller = new VariantCaller(
				getContext(),
				OUTPUT,
				samEvidence,
				assemblyEvidence,
				evidenceDump);
			caller.callBreakends(threadpool);
			caller.annotateBreakpoints(TRUTH_VCF);
		} finally {
			if (caller != null) caller.close();
		}
	}
	private void hackSimpleCalls() throws IOException {
		if (OUTPUT.exists()) {
			File simpleHack = new File(OUTPUT.getParent(), OUTPUT.getName() + ".simple.vcf");
			if (!simpleHack.exists()) {
				new BreakendToSimpleCall(getContext()).convert(OUTPUT, simpleHack);
			}
		}
	}
	private void compoundRealignment(ExecutorService threadpool, List<? extends EvidenceSource> evidence) throws InterruptedException, ExecutionException {
		log.info("Checking for nested realignment");
		for (Future<Void> future : threadpool.invokeAll(Lists.transform(evidence, new Function<EvidenceSource, Callable<Void>>() {
			@Override
			public Callable<Void> apply(final EvidenceSource input) {
				return new Callable<Void>() {
					@Override
					public Void call() throws Exception {
						try {
							input.iterateRealignment();
						} catch (Exception e) {
							log.error(e, "Exception thrown by worker thread");
							throw e;
						}
						return null;
					}
				};
			}
		}))) {
			// throw exception from worker thread here
			future.get();
		}
		log.info("Nested realignment checking complete");
	}
    private boolean checkRealignment(List<? extends EvidenceSource> evidence, int threads) throws IOException {
    	String instructions = getRealignmentScript(evidence, threads);
    	if (instructions != null && instructions.length() > 0) {
    		log.error("Please realign intermediate fastq files. Suggested command-line for alignment is:\n" +
    				"##################################\n"+
    				instructions +
    				"##################################");
	    	log.error("Please rerun after alignments have been performed.");
	    	if (SCRIPT != null) {
	    		FileWriter writer = null;
	    		try {
	    			writer = new FileWriter(SCRIPT);
	    			writer.write(instructions);
	    		} finally {
	    			if (writer != null) writer.close(); 
	    		}
	    		log.error("Realignment script has been written to ", SCRIPT);
	    	}
	    	return false;
    	}
    	return true;
	}
	private void shutdownPool(ExecutorService threadpool) {
    	if (threadpool != null) {
			log.debug("Shutting down thread pool.");
			threadpool.shutdownNow();
			log.debug("Waiting for thread pool tasks to complete");
			try {
				if (!threadpool.awaitTermination(10, TimeUnit.MINUTES)) {
					log.error("Tasks did not respond to termination request in a timely manner - outstanding tasks will be forcibly terminated without cleanup.");
				}
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
    }
	public static void main(String[] argv) {
        System.exit(new Idsv().instanceMain(argv));
    }
}
