#!/bin/bash
# $1 is sample id
# $2 is the sam/bam/cram
sample=$1
cram=$2
bucket_user=$3
database=$4
outdir=$5
ref=/opt/resources/reference_genome/37/Homo_sapiens.GRCh37.GATK.illumina.fasta
db=/data/db/virusbreakenddb_$database
export PATH=/opt/tools/repeatmasker/4.1.1:$PATH
export PATH=/opt/tools/trf/4.0.9:$PATH
export PATH=/opt/tools/rmblast/2.10.0/bin:$PATH
export PATH=/opt/tools/rmblast/2.10.0:$PATH
export PATH=/opt/tools/hmmer/3.3/bin:$PATH
export PATH=/opt/tools/hmmer/3.3:$PATH
export PATH=/opt/tools/gridss/2.10.0:$PATH
export PATH=/opt/tools/kraken2/2.1.0:$PATH
export PATH=/opt/tools/samtools/1.10:$PATH
export PATH=/opt/tools/bcftools/1.9:$PATH
export PATH=/opt/tools/bwa/0.7.17:$PATH
export PATH=.:$PATH
export GRIDSS_JAR=/data/gridss-2.11.0-gridss-jar-with-dependencies.jar
mkdir -p /data
cd /data
gsutil -u $bucket_user -m cp -r gs://virusbreakend/bin/*.jar .
gsutil -u $bucket_user -m cp -r gs://virusbreakend/bin/*.sh .
gsutil -u $bucket_user -m cp -r gs://virusbreakend/db .
chmod +x *.sh
# Nifty hack in which we don't even need to download the file: we can stream directly from the bucket
# Note that we need to encapulate the redirect as a string and not as the file descriptor itself
gce_file_direct_from_bucket="<( gsutil -u $bucket_user cat $cram )"
./virusbreakend.sh --force -t $(nproc) -r $ref --db $db -o ${sample}.virusbreakend.vcf --jar $GRIDSS_JAR --gridssargs "--jvmheap 13g" "$gce_file_direct_from_bucket" 2>&1 > ${sample}.virusbreakend.log
gsutil -u $bucket_user -m cp -r ${sample}.* gs://virusbreakend/$outdir/
#sudo shutdown -P now
