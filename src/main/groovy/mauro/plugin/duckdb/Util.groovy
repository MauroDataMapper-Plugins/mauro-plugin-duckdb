package mauro.plugin.duckdb

import org.maurodata.domain.datamodel.DataElement
import org.maurodata.domain.facet.Metadata

import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class Util
{
    final static long MAX_ENUMERATION_VALUES = 80
    final static long MAX_ENUMERATION_VALUE_LENGTH = 100

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
        long binInterval=makeBinIntervalValue((highest_value-lowest_value).intdiv(numberOfBins));
        if(binInterval<=0){binInterval=1;}
        return binInterval;
    }

    static long makeBinIntervalValue(final long range)
    {
        final double digits=Math.floor(Math.log10((double) range))
        return (long) Math.pow(10,digits);
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

    /*
    https://duckdb.org/docs/sql/data_types/overview.html
    */

    static final String[] TYPES_INT=['TINYINT','SMALLINT','INTEGER','BIGINT','HUGEINT','UTINYINT','USMALLINT','UINTEGER','UBIGINT','UHUGEINT']
    static final String[] TYPES_STRING=['VARCHAR','CHAR','BPCHAR','STRING','TEXT']
    static final String[] TYPES_DATE=['DATE']
    static final String[] TYPES_DATETIME=['TIMESTAMP_NS','TIMESTAMP','TIMESTAMP_MS','TIMESTAMP_S','TIMESTAMPZ','TIME','TIMEZ']
    static final String[] TYPES_DECIMAL=['FLOAT','FLOAT4','REAL','DOUBLE','FLOAT8','DECIMAL','NUMERIC']

    static boolean isINT(final String SQLType) { return SQLType.toUpperCase() in TYPES_INT }
    static boolean isSTRING(final String SQLType) { return SQLType.toUpperCase() in TYPES_STRING }
    static boolean isDATE(final String SQLType) { return SQLType.toUpperCase() in TYPES_DATE }
    static boolean isDATETIME(final String SQLType) { return SQLType.toUpperCase() in TYPES_DATETIME }
    static boolean isDECIMAL(final String SQLType) { return SQLType.toUpperCase() in TYPES_DECIMAL }

    static String getMauroDataType(final String SQLType)
    {
        if(isINT(SQLType)){return 'integer'}
        if(isSTRING(SQLType)) {return 'string'}
        if(isDATE(SQLType)) {return 'date'}
        if(isDATETIME(SQLType)) {return 'datetime'}
        if(isDECIMAL(SQLType)) {return 'decimal'}

        return 'string'
    }

    static boolean isString(DataElement dataElement) { return isSTRING(dataElement.dataType.label) }

    static boolean isNumeric(DataElement dataElement)
    {
        return isINT(dataElement.dataType.label) || isDECIMAL(dataElement.dataType.label)
    }

    static boolean isInteger(DataElement dataElement) { return isINT(dataElement.dataType.label) }

    static boolean isDate(DataElement dataElement)
    {
        return isDATE(dataElement.dataType.label) || isDATETIME(dataElement.dataType.label)
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

    /* Check for existence of metadata */

    static Metadata getMetadata(final List<Metadata> metadataList, final String namespace, final String key)
    {
        metadataList.find {it.key == key && it.namespace == namespace}
    }

    static String sanitiseForMauroLabel(final String label)
    {
        if(label.indexOf('|')==-1 && label.indexOf('@')==-1 && label.indexOf('$')==-1)
        {
            return label;
        }
        return label.replace('|',':').replace('@',' ').replace('$','_');
    }

}
