#!/bin/bash
getopt --test
if [[ ${PIPESTATUS[0]} -ne 4 ]]; then
	echo 'WARNING: "getopt --test"` failed in this environment.' 1>&2
	echo "WARNING: The version of getopt(1) installed on this system might not be compatible with the GRIDSS driver script." 1>&2
fi
set -o errexit -o pipefail -o noclobber -o nounset
last_command=""
current_command=""
trap 'last_command=$current_command; current_command=$BASH_COMMAND' DEBUG
trap 'echo "\"${last_command}\" command completed with exit code $?."' EXIT
#253 forcing C locale for everything
export LC_ALL=C

EX_USAGE=64
EX_NOINPUT=66
EX_CANTCREAT=73
EX_CONFIG=78

dbname=virusbreakenddb
kraken2buildargs=""
#kraken2buildargs="--source genbank"  # https://github.com/DerrickWood/kraken2/pull/418
USAGE_MESSAGE="
VIRUSBreakend database build

Downloads and builds the databases used by VIRUSBreakend

This program requires kraken2-build and samtools to be on PATH

Usage: virusbreakend-build.sh [--db $dbname]
	--db: directory to create database in (Default: $dbname)
	-j/--jar: location of GRIDSS jar
	--kraken2buildargs: additional kraken2-build arguments (Default: $kraken2buildargs)
	-h/--help: display this message"
OPTIONS=hj:
LONGOPTS=help,db:,jar:
! PARSED=$(getopt --options=$OPTIONS --longoptions=$LONGOPTS --name "$0" -- "$@")
if [[ ${PIPESTATUS[0]} -ne 0 ]]; then
    # e.g. return value is 1
    #  then getopt has complained about wrong arguments to stdout
	echo "$USAGE_MESSAGE" 1>&2
    exit $EX_USAGE
fi
eval set -- "$PARSED"
while true; do
	case "$1" in
		-h|--help)
			echo "$USAGE_MESSAGE" 1>&2
			exit 0
			;;
		--db)
			dbname=$2
			shift 2
			;;
		--kraken2buildargs)
			kraken2buildargs=$2
			shift 2
			;;
		-j|--jar)
			GRIDSS_JAR="$2"
			shift 2
			;;
		--)
			shift
			break
			;;
		*)
			echo "Programming error"
			exit 1
			;;
	esac
done
if [[ "$@" != "" ]] ; then
	echo "$USAGE_MESSAGE" 1>&2
	exit $EX_USAGE
fi
write_status() {
	echo "$(date): $1" 1>&2
}
for tool in kraken2-build samtools gunzip tar dustmasker rsync java wget ; do
	if ! which $tool >/dev/null; then
		echo "Error: unable to find $tool on \$PATH" 1>&2
		exit $EX_CONFIG
	fi
	write_status "Found $(which $tool)"
done
### Find the jars
find_jar() {
	env_name=$1
	if [[ -f "${!env_name:-}" ]] ; then
		echo "${!env_name}"
	else
		write_status "Unable to find $2 jar. Specify using the environment variant $env_name, or the --jar command line parameter."
		exit $EX_NOINPUT
	fi
}
gridss_jar=$(find_jar GRIDSS_JAR gridss)
write_status "Using GRIDSS jar $gridss_jar"

kraken2-build $kraken2buildargs --download-taxonomy --db $dbname
kraken2-build $kraken2buildargs --download-library human --db $dbname
kraken2-build $kraken2buildargs --download-library viral --db $dbname
kraken2-build $kraken2buildargs --download-library UniVec_Core --db $dbname
kraken2-build $kraken2buildargs --download-library bacteria --db $dbname
kraken2-build $kraken2buildargs --download-library archaea --db $dbname
#  AND ("vhost human"[Filter]) 
esearch -db nuccore -query "Viruses[Organism] NOT cellular organisms[ORGN] NOT wgs[PROP] NOT AC_000001:AC_999999[pacc] NOT gbdiv syn[prop] AND (srcdb_refseq[PROP] OR nuccore genome samespecies[Filter])" \
| efetch -format fasta > $dbname/neighbour.fa
kraken2-build --add-to-library $dbname/neighbour.fa --db $dbname
# Issue with neighbours: serotypes (e.g. HPV-45) are listed as neighbours of the species taxid
# Use Neighbours for host taxid filtering. TODO: get a better source
wget --output-document=$dbname/taxid10239.nbr "https://www.ncbi.nlm.nih.gov/genomes/GenomesGroup.cgi?taxid=10239&cmd=download2"
#accessions=$(tail -n+3 taxid10239.nbr | cut -f 2 | tr '\n' ,)
#awk 'NR==FNR{print "Array:" $2 "->" $3; a[">"$2]=$3;next} /^>/ { $1=">"$1 p=index($1," "); print $1 ", " $2 ", " $3; substr(0,p); }' test.nucl_gb.accession2taxid test.fa
# get all the neighbour genomes
# map back to taxid using nucl_gb.accession2taxid since HPV serotypes are neighbours of the species RefSeq genome
#grep $KRAKEN2_DIR $(which kraken2-build) /home/daniel/miniconda3/envs/kraken2/libexec
# Genomes:
# https://ftp.ncbi.nlm.nih.gov/genomes/genbank/viral/assembly_summary.txt
#cut -f 20 assembly_summary.txt | tail -n+3 | sed 's/ftp:\/\/ftp.ncbi.nlm.nih.gov\/genomes//' | sed -E 's/([^/]+)$/\1\/\1_genomic.fna.gz/' | sort > fna_urls.txt
#rsync -av --files-from=fna_urls.txt rsync://ftp.ncbi.nlm.nih.gov/genomes/ genomes/
# TODO why does masking result in empty .mask files?
kraken2-build $kraken2buildargs --build --db $dbname
for f in $(find $dbname/ -name '*.fna') ; do
	samtools faidx $f
	rm -f $f.dict
	java -cp $GRIDSS_JAR \
		picard.cmdline.PicardCommandLine \
		CreateSequenceDictionary \
		R=$f \
		O=$f.dict
done

tar -czvf virusbreakend.db.$(basename $dbname).tar.gz \
	$(basename $dbname)/*.k2d \
	$(basename $dbname)/taxonomy/nodes.dmp \
	$(basename $dbname)/library/viral/*.fna* \
	$(basename $dbname)/library/added/*.fna* \
	$(basename $dbname)/taxid10239.nbr \
	$(basename $dbname)/seqid2taxid.map

write_status "VIRUSBreakend build successful"
write_status "The full build (including intermediate files) can be found in $dbname"
write_status "An archive containing only the files required by VIRUSBreakend can be found at $(realpath $dbname/../virusbreakend.db.$(basename $dbname).tar.gz)"

trap - EXIT
exit 0 # success!

