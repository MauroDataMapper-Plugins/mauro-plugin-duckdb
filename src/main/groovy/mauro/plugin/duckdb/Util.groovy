package mauro.plugin.duckdb

import uk.ac.ox.softeng.mauro.domain.datamodel.DataElement
import uk.ac.ox.softeng.mauro.domain.facet.Metadata
import uk.ac.ox.softeng.mauro.domain.model.AdministeredItem

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class Util
{
    final static long MAX_ENUMERATION_VALUES = 50
    final static long MAX_ENUMERATION_VALUE_LENGTH = 100
    final static long SUMMARY_METADATA_FLOOR = 0

    // date parsing
    final static DateTimeFormatter iso_local=DateTimeFormatter.ISO_LOCAL_DATE;

    static LocalDate parseISO_LOCAL_DATE(final String dateString /* yyyy-MM-dd HH:mm:ss.S */) throws DateTimeParseException
    {
        // Remove everything after the space

        final int space=dateString.indexOf(' ');
        final String yyyyMMdd;

        if(space!=-1) {
            yyyyMMdd=dateString.substring(0, space);
        }
        else
        {
            yyyyMMdd=dateString;
        }

        LocalDate date=LocalDate.parse(yyyyMMdd,iso_local);
        return date;
    }

    /*
    Functions for getting data bin intervals
     */
    static long makeBinInterval(final long distinctValuesCount,final long lowest_value, final long highest_value)
    {
        long numberOfBins = (long) Math.max(3,Math.min(8,Math.ceil(Math.log10(distinctValuesCount))));
        long binInterval=makeLowestIntervalValueForBin((highest_value-lowest_value).intdiv(numberOfBins));
        if(binInterval<=0){binInterval=1;}
        return binInterval;
    }

    static long makeLowestIntervalValueForBin(final long min_value)
    {
        final double digits=Math.max(1.0D,Math.floor(Math.log10((double) min_value))-1);
        long multiplier=(long) Math.pow(10,digits);

        if(multiplier==0){multiplier=1;}

        return min_value.intdiv(multiplier) * multiplier;
    }

    static long makeHighestIntervalValueForBin(final long max_value)
    {
        final double digits=Math.max(1.0D,Math.floor(Math.log10((double) max_value))-1);
        long multiplier=(long) Math.pow(10,digits);

        if(multiplier==0){multiplier=1;}

        return (max_value.intdiv(multiplier) * multiplier) + multiplier;
    }

    /* Some stats functions on maps of category -> long (output of binned or enumerated data) */

    static float calculateEntropy(final Map<String,Long> frequencyMap)
    {
        long sum=0;

        {
            Iterator<String> valueLabelIterator=frequencyMap.keySet().iterator();
            while(valueLabelIterator.hasNext())
            {
                final String valueLabel=valueLabelIterator.next();
                if(
                        "<null>" == valueLabel ||
                                "<blank>" == valueLabel ||
                                "<no value>" == valueLabel
                )
                {
                    continue
                }

                final long frequency = frequencyMap.get(valueLabel)
                sum += frequency;
            }
        }

        if(sum==0){return 0f;}

        double entropy=0;
        double k=0;
        {
            Iterator<String> valueLabelIterator=frequencyMap.keySet().iterator();
            while(valueLabelIterator.hasNext())
            {
                final String valueLabel=valueLabelIterator.next();
                if(
                        "<null>" == valueLabel ||
                                "<blank>" == valueLabel ||
                                "<no value>" == valueLabel
                )
                {
                    continue
                }
                final long frequency = frequencyMap.get(valueLabel)
                final double p=(double) frequency.div((double) sum);
                final double pxlog=(p * Math.log10(p));
                entropy += pxlog ;
                k++;
            }
        }

        // Normalised
        entropy = entropy / Math.log10(k);

        // 2dp
        entropy = Math.round(entropy * 100)/100.0;
        entropy=Math.abs(entropy)

        return (float) entropy;
    }

    static float calculateImbalance(final Map<String,Long> frequencyMap)
    {
        long sum=0;
        long maxFrequency=0;

        {
            Iterator<String> valueLabelIterator=frequencyMap.keySet().iterator();
            while(valueLabelIterator.hasNext())
            {
                final String valueLabel=valueLabelIterator.next();
                if(
                        "<null>" == valueLabel ||
                                "<blank>" == valueLabel ||
                                "<no value>" == valueLabel
                )
                {
                    continue
                }
                final long frequency = frequencyMap.get(valueLabel)
                sum += frequency;

                if(frequency>maxFrequency){maxFrequency=frequency}
            }
        }

        if(sum==0){return 1f;}

        double imbalance=(double) maxFrequency/(double) sum;
        imbalance = Math.round(imbalance * 100)/100.0;

        return (float) (imbalance);
    }

    static float calculateSuggestionIndex(final Map<String,Long> frequencyMap)
    {
        final float entropy=calculateEntropy(frequencyMap)
        final float imbalance=calculateImbalance(frequencyMap)

        double suggestionIndex= (0.6D* entropy) +  (0.4D*(1.0D- imbalance))

        return (float) Math.round(suggestionIndex * 100)/100.0;
    }

    // When the data can't be put into bins yet we know the number of distinct values
    // as well as the total number of rows, this is an estimate for the entropy
    // Because the imbalance for this case happens to be 1-entropy and the suggestion index is (currently) the
    // weighted sum of the two, the suggestion index equals this basic estimated entropy
    static float calculateBasicEntropy(final long distinctValues,final long totalNumberOfValues)
    {
        if(distinctValues == 0)
        {
            return 0;
        }
        final double H_est=Math.log10(distinctValues) / Math.log10(totalNumberOfValues)

        double entropy = Math.round(H_est * 100)/100.0;
        entropy=Math.abs(entropy)

        return (float) (entropy);
    }

    /* */

    static isString(DataElement dataElement) {
        dataElement.dataType.label in ['STRING']
    }

    /*
    https://docs.databricks.com/en/sql/language-manual/sql-ref-datatypes.html
    */

    static isNumeric(DataElement dataElement) {
        dataElement.dataType.label in ['BIGINT', 'DECIMAL', 'DOUBLE', 'FLOAT', 'INT', 'SMALLINT', 'TINYINT', 'LONG']
    }

    static isInteger(DataElement dataElement) {
        dataElement.dataType.label in ['BIGINT', 'INT', 'SMALLINT', 'TINYINT', 'LONG']
    }

    static isDate(DataElement dataElement) {
        dataElement.dataType.label in ['DATE', 'TIMESTAMP','TIMESTAMP_NTZ']
    }

    static String escapeIdentifier(String identifier) {
        identifier = identifier.toLowerCase()
        if (!(identifier ==~ /[\w-\/\[\]:&]+/)) {
            throw new IllegalArgumentException("Identifier [$identifier] contains invalid character(s)")
        }
        if (identifier.contains('-') || identifier.contains('/') || identifier.contains(':') || identifier.contains('&')) identifier = "`$identifier`"
        identifier
    }

    static String normaliseEnumerationValueSql(String identifier) {
        //"coalesce(nullif(coalesce(regexp_replace(regexp_replace(trim(substr($identifier, 1, $MAX_ENUMERATION_VALUE_LENGTH)), '\\\\p{Space}+', ' '),'[\\\\P{Print}@|\$]', '�'), '<null>'), ''), '<blank>')" // regexp_replace is slow on big tables
        // replace Mauro disallowed label characters with '�', convert nulls to '<null>', blanks to '<blank>'
        "replace(nvl(nullif(nvl(translate(trim(substr($identifier, 1, $MAX_ENUMERATION_VALUE_LENGTH)), '@|\$\\0', '���'), '<null>'), ''), '<blank>'), '\\\\', '\\\\\\\\')" // todo check
    }

    static String lookupCodeDescriptionSql(final String display, final String value, final String table, final  String identifier){
        return '(select first('+display+') from '+table+' where '+value+' = '+identifier+')';
    }

    static List<Map<String,Object>> resultSetToList(ResultSet resultSet) {
        ResultSetMetaData resultSetMetaData = resultSet.getMetaData()
        List<Map> results = []
        while (resultSet.next()) {
            results << (1..resultSetMetaData.getColumnCount()).collectEntries {Integer i ->
                [resultSetMetaData.getColumnName(i), resultSet.getObject(i)]
            }
        }
        return results;
    }

    static String normaliseLabelCase(String label) {
        label.replaceAll(/(_|^)([a-zA-Z0-9]*)/, {it[1] + it[2].toLowerCase().capitalize()})
    }

    static AdministeredItem addResultsAsMetadata(AdministeredItem item, Map<String, Object> results) {
        results.findAll {it.key && it.value}.each {
            item.metadata.add(new Metadata(namespace: this.class.packageName, key: it.key, value: it.value))
        }
        item
    }

    /* Check for existence of metadata */

    static Metadata getMetadata(final List<Metadata> metadataList,final String namespace,final String key)
    {
        metadataList.find {it.key == key && it.namespace == namespace}
    }
}
