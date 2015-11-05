# gridss

A high-speed next-gen sequencing structural variation caller.
gridss calls variants based on alignment-guided positional de Bruijn graph breakpoint assembly, split read, and read pair evidence.

# Pre-requisities

To run, gridss the following must be installed:

* Java 1.8 or later
* NGS aligner. bowtie2 (default) and bwa are currently supported

# Running

Pre-compiled binaries are available at https://github.com/PapenfussLab/gridss/releases.

Gridss is built using htsjdk, so is invoked in the same manner as Picard tools utilities. Gridss invokes an external alignment tools at multiple point during processing. By default this is bowtie2, but can be configured to use bwa mem.

## example/gridss.sh

example/gridss.sh contains an example pipeline of how gridss is invoked.

## Memory usage

It is recommended to run gridss with max heap memory of 8GB + 2GB per worker thread.
For example, with 4 worker threads, it is recommended to run gridss with is -Xmx16g.
Note that if a BED blacklist file excluding problematic centromeric and telomeric
sequences (the ENCODE DAC blacklist is recommended when aligning against hg19),
additional memory is recommended.

## Parameters

Gridss has a large number of parameters that can be be adjusted. The default parameter set has been tested with paired-end illumina data ranging from 2x36bp to 2x250bp and should give a reasonably good. Command line used parameter are listed below.

### OUTPUT (Required)

Variant calling output file. Can be VCF or BCF.

### REFERENCE (Required)

Reference genome fasta file. Gridss requires the reference genome supplied exactly matches
the reference genome all input files.
The reference genome must be be fasta format and must have a tabix (.fai) index and an
index for the NGS aligner (by default bowtie2). The NGS aligner index prefix must match
the reference genome filename. For example, using the default setting against the reference
file reference.fa, the following files must be present and readable:

File | Description
------- | ---------
reference.fa | reference genome
reference.fa.fai | Tabix index
reference.fa.1.bt2 | Bowtie2 index
reference.fa.2.bt2 | Bowtie2 index
reference.fa.3.bt2 | Bowtie2 index
reference.fa.4.bt2 | Bowtie2 index
reference.fa.rev.1.bt2 | Bowtie2 index
reference.fa.rev.1.bt2 | Bowtie2 index

These can be created using `samtools faidx reference.fa` and  `bowtie2-build reference.fa reference.fa`

A .dict sequence dictionary is also required but gridss will automatically create one if not found. 

### INPUT (Required)

Input libraries. Specify multiple times (ie `INPUT=file1.bam INPUT=file2.bam INPUT=file3.bam` ) to process multiple libraries together.
Gridss considers all reads in each file to come from a single library.
Input files containing read groups from multiple different libraries should be split into an input file per-library.

### INPUT_CATEGORY

Numeric category (starting at zero) to allocate the corresponding input file to. Per-category variant support is output so
a category should be specified for each input file when performing analysis on multiple samples at once. (eg `INPUT=normal75bp.bam INPUT_CATEGORY=0 INPUT=normal100bp.bam INPUT_CATEGORY=0 INPUT=tumour100bp.bam INPUT_CATEGORY=1` ).

### READ_PAIR_CONCORDANT_PERCENT

Portion (0.0-1.0) of read pairs to be considered concordant. Concordant read pairs are considered to provide no support for structural variation.
Clearing this value will cause gridss to use the 0x02 proper pair SAM flag written by the aligner to detemine concordant pairing.
Note that some aligner set this flag in a manner inappropriate for SV calling and set the flag for all reads with the expected orientation
and strand regardless of the inferred fragment size.

### INPUT_MIN_FRAGMENT_SIZE, INPUT_MAX_FRAGMENT_SIZE

Per input overrides for explicitly specifying fragment size interval to be considered concordant. As with INPUT_CATEGORY, these must be specified
for all input files. Use null to indicate an override is not required for a particular input (eg
`INPUT=autocalc.bam INPUT_MIN_FRAGMENT_SIZE=null INPUT_MAX_FRAGMENT_SIZE=null INPUT=manual.bam INPUT_MIN_FRAGMENT_SIZE=100 INPUT_MAX_FRAGMENT_SIZE=300` )

### PER_CHR

Flags whether processing should be performed in parallel for each chromosome, or serially for each file.
If your input files are small, or you have a limited number of file handles available, `PER_CHR=false` will
reduce the number of intermediate files created, at the cost of reduced parallelism.

### WORKER_THREADS

Number of processing threads to use, including number of thread to use when invoking the aligner.
Note that the number of threads spawned by gridss is typically 2-3 times the number of worker threads due to asynchronous I/O threads
thus it is not uncommon to see over 100% CPU usage when WORKER_THREADS=1 as bam compression/decompression is a computationally expensive operation.
This parameter defaults to the number of cores available.

### WORKING_DIR

Directory to write intermediate results directories. By default, intermediate files for each input or output file are written to a subdirectory in the same directory as the relevant input or output file.
If WORKING_DIR is set, all intermediate results are written to subdirectories of the given directory.

### TMP_DIR

This field is a standard Picard tools argument and carries the usual meaning. Temporary files created during processes such as sort are written to this directory.

## libsswjni.so

Due to relatively poor performance of existing Java-based Smith-Waterman alignment packages, gridss incorporates a JNI wrapper to the striped Smith-Waterman alignment library [SSW](https://github.com/mengyao/Complete-Striped-Smith-Waterman-Library). Gridss will attempt to load a precompiled version. If the precompiled version is not compatible with your linux distribution, or you are running a different operating system, recompilation of the wrapper from source will be required. When recompiling, ensure the correct libsswjni.so is loaded using -Djava.library.path, or the LD_LIBRARY_PATH environment variable as per the JNI documentation.

If your CPU does not support SSE, gridss will terminate with a fatal error when loading the library. Library loading can be disabled by added `-Dsswjni.disable=true` to the gridss command line. If libsswjni.so cannot be loaded, gridss will fall back to a (50x) slower java implementation. 

### CONFIGURATION_FILE

Gridss uses a large number of configurable settings and thresholds which for easy of use are not included
as command line arguments. Any of these individual settings can be overriden by specifying a configuration
file to use instead. Note that this configuration file uses a different format to the Picard tools-compatable
configuration file that is used instead of the standard command-line arguments.

When supplying a custom configuration, gridss will use the overriding settings for all properties specified
and fall back to the default for all properties that have not been overridden. Details on the meaning
of each parameter can be found in the javadoc documentation of the au.edu.wehi.idsv.configuration classes.

# Output

Gridss is fundamentally a structural variation breakpoint caller. Variants are output as VCF breakends. Each call is a breakpoint consisting of two breakends, one from location A to location B, and a reciprocal record from location B back to A. Note that although each record fully defines the call, the VCF format required both breakend to be written as separate record.

## Quality score

Gridss calculates quality scores according to the model outlined in [paper].
As gridss does not yet perform multiple test correction or score recalibration, QUAL scores are vastly overestimated for all variants.
As a rule of thumb, variants with QUAL >= 1000 and have assembles from both sides of the breakpoint (AS > 0 & RAS > 0) are considered of high quality,
variant with QUAL >= 500 but can only be assembled from one breakend (AS > 0 | RAS > 0) are considered of intermediate quality,
and variants with low QUAL score or lack any supporting assemblies are considered the be of low quality.

## Non-standard INFO fields

Gridss writes a number of non-standard VCF fields. These fields are described in the VCF header.

## BEDPE

Gridss supports conversion of VCF to BEDPE format using the VcfBreakendToBedpe utility program included in the gridss jar.
An working example of this conversion utility is provided in example/gridss.sh

Calling VcfBreakendToBedpe with `INCLUDE_HEADER=true` will include a header containing column names in the bedpe file.
These fields match the VCF INFO fields of the same name.
For bedpe output, breakend information is not exported and per category totals (such as split read counts) are aggregated to a single value.

## Intermediate Files

File | Description
------- | ---------
tmp.* | Temporary intermediate file
unsorted.* | Temporary intermediate file
*.bai | BAM index for coordinate sorted intermediate BAM file
*input*.idsv.working | Working directory for files related to the given input file.
*input*.idsv.working/*input*.idsv.metrics.insersize.txt | Picard tools CollectInsertSizeMetrics output 
*input*.idsv.working/*input*.idsv.metrics.idsv.txt | High-level read/read pair metrics
*input*.idsv.working/*input*.idsv.metrics.softclip.txt | Soft clip length distribution
*input*.idsv.working/*input*.idsv.coverage.blacklist.bed | Intervals of extreme coverage excluded from variant calling
*input*.idsv.working/*input*.idsv.*chr*.sc.bam | soft clipped reads (coordinate sort order)
*input*.idsv.working/*input*.idsv.*chr*.realign.0.fq | soft clipped bases requiring realignment. The source soft clip is encoded in the read bam
*input*.idsv.working/*input*.idsv.*chr*.realign.0.bam | soft clipped bases aligned by external aligner. **Record order must match the fastq record order**
*input*.idsv.working/*input*.idsv.*chr*.scremote.bam | soft clipped reads (realignment position sort order)
*input*.idsv.working/*input*.idsv.*chr*.realignremote.bam | soft clipped reads (realignment position sort order)
*input*.idsv.working/*input*.idsv.*chr*.rp.bam | discordant read pairs
*input*.idsv.working/*input*.idsv.*chr*.rpmate.bam | discordant read pairs, sorted by alignment position of the other read in the pair
*output*.idsv.working | Working directory for assembly and variant calling
*output*.idsv.working/*output*.idsv.breakpoint.vcf | Raw maximal clique variant calls not requiring unique evidence assignment
*output*.idsv.working/*output*.idsv.assembly.bam | Assembly contigs represented as paired reads. The first read in the read pair is the assembly, with the second corresponding to the realignment of the breakend bases. Note that the assembly is always on the positive strand so an assembly of a simple indel result in a read pair with FF read alignment orientation. Assemblies with read names of the form asm*n*_*i* are compound realignment records, with the actual assembly in the asm*n* record.
*output*.idsv.working/*output*.idsv.*chr*.assembly.bam | *chr* subset of above
*output*.idsv.working/*output*.idsv.*chr*.assemblymate.bam | above sorted by mate position
*output*.idsv.working/*output*.idsv.*chr*.realign.*n*.fq | breakend realignment iteration *n*. Breakends that span events (such as small translocations or chromothripsis), are realigned multiple times to identify all fusions spanned by the assembly
*output*.idsv.working/*output*.idsv.*chr*.realign.*n*.bam | External realigner alignments of above. **Record order must match the fastq record order**
*output*.idsv.working/*output*.idsv.*chr*.breakend.bam | Raw assembly contigs before realignment
*output*.idsv.working/*output*.idsv.*chr*.breakend.throttled.bam | Regions of high coverage where only a portion of supporting reads are considered for assembly

## Building from source

Maven is used for build and dependency management which simplifies compile to the following steps:
Gridss write a large number of intermediate files. If rerunning gridss with parametere on the same input, the intermediate files should be deleted. All intermediate files are written to the WORKING_DIR directory tree, with the exception of temporary sort buffers written to TMP_DIR and automatically deleted at the conclusion of the sort operation.

* `git clone https://github.com/PapenfussLab/gridss`
* `mvn package -DskipTests`

If gridss was built successfully, a combined jar containing gridss and all required library located at target/gridss-*-jar-with-dependencies.jar will have been created.










