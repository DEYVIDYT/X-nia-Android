<?php
/**
 * Parses a size string (e.g., "1.2GB", "500 MB", "700kb", "14,5MB") into Megabytes (MB).
 *
 * @param string|null $sizeStr The size string to parse.
 * @return float|null The size in MB as a float, or null if parsing fails.
 */
function parseSizeToMB($sizeStr) {
    if ($sizeStr === null || trim($sizeStr) === '') {
        return null;
    }

    // Normalize: to uppercase, trim whitespace, replace comma with period for floatval
    $normalizedStr = strtoupper(trim($sizeStr));
    $normalizedStr = str_replace(',', '.', $normalizedStr);

    // Regular expression to extract the numeric value and the unit
    // It looks for a number (integer or decimal) followed by optional whitespace and then GB, MB, or KB.
    // If no unit is found after a number, it will attempt to treat the number as MB by default if only a number is present.
    if (preg_match('/^([0-9\.]+)\s*(GB|MB|KB)?$/', $normalizedStr, $matches)) {
        $value = floatval($matches[1]);
        $unit = isset($matches[2]) ? $matches[2] : 'MB'; // Default to MB if no unit explicitly matched after number

        switch ($unit) {
            case 'GB':
                return $value * 1024;
            case 'KB':
                return $value / 1024;
            case 'MB':
            default: // Also covers the case where unit was 'MB' or defaulted to 'MB'
                return $value;
        }
    } else if (is_numeric($normalizedStr)) {
        // If the string is purely numeric after normalization (e.g. "1500" was input)
        // Treat as MB. This is a fallback.
        return floatval($normalizedStr);
    }

    // If regex fails and it's not purely numeric, parsing failed.
    return null;
}

// Test cases (for demonstration, not part of the function itself)
/*
echo "Testing parseSizeToMB:\n";
var_dump(parseSizeToMB("1.5GB"));     // Expected: float(1536)
var_dump(parseSizeToMB("1,5gb"));     // Expected: float(1536)
var_dump(parseSizeToMB("512MB"));     // Expected: float(512)
var_dump(parseSizeToMB("512 mb"));    // Expected: float(512)
var_dump(parseSizeToMB("1024KB"));    // Expected: float(1)
var_dump(parseSizeToMB("1024 kb"));   // Expected: float(1)
var_dump(parseSizeToMB("2048"));      // Expected: float(2048) (as MB due to fallback)
var_dump(parseSizeToMB("100"));       // Expected: float(100) (as MB)
var_dump(parseSizeToMB("1.234"));     // Expected: float(1.234) (as MB)
var_dump(parseSizeToMB("test"));      // Expected: NULL
var_dump(parseSizeToMB("100 TB"));    // Expected: NULL (TB not handled)
var_dump(parseSizeToMB(""));          // Expected: NULL
var_dump(parseSizeToMB(null));        // Expected: NULL
var_dump(parseSizeToMB("100MBB"));    // Expected: NULL (current regex is strict on unit suffix)
var_dump(parseSizeToMB("MB500"));     // Expected: NULL
*/

?>
