<?php
header("Content-Type: application/json; charset=UTF-8");

$jsonFile = __DIR__ . "/games.json"; // Use __DIR__ for reliable path

// --- Helper function to safely get numeric value from an array key ---
// Returns null if the key is not set or the value is not numeric.
function getNumericValue($array, $key) {
    if (!isset($array[$key]) || !is_numeric($array[$key])) {
        return null;
    }
    return floatval($array[$key]);
}

// --- Default sort parameters ---
$sortByInput = filter_input(INPUT_GET, 'sort_by', FILTER_SANITIZE_STRING);
$sortBy = 'date'; // Default sort field
if (!empty($sortByInput) && in_array($sortByInput, ['date', 'name', 'size'])) {
    $sortBy = $sortByInput;
}

$sortOrderInput = filter_input(INPUT_GET, 'sort_order', FILTER_SANITIZE_STRING);
$sortOrder = 'desc'; // Default sort order (newest first for date)
if (!empty($sortOrderInput) && in_array(strtolower($sortOrderInput), ['asc', 'desc'])) {
    $sortOrder = strtolower($sortOrderInput);
}

// --- Filter parameters ---
$minSizeMb = filter_input(INPUT_GET, 'min_size_mb', FILTER_VALIDATE_FLOAT, FILTER_NULL_ON_FAILURE);
$maxSizeMb = filter_input(INPUT_GET, 'max_size_mb', FILTER_VALIDATE_FLOAT, FILTER_NULL_ON_FAILURE);

$searchNameInput = filter_input(INPUT_GET, 'search_name', FILTER_SANITIZE_STRING);
$searchName = null;
if ($searchNameInput !== null) {
    $searchNameTrimmed = trim($searchNameInput);
    if (!empty($searchNameTrimmed)) {
        $searchName = $searchNameTrimmed;
    }
}

// --- Load games data ---
$games = [];
if (file_exists($jsonFile)) {
    $currentData = file_get_contents($jsonFile);
    if ($currentData !== false) {
        $decodedGames = json_decode($currentData, true);
        if (is_array($decodedGames)) {
            $games = $decodedGames;
        }
    }
}

// --- Filter games ---
$filteredGames = $games;

// 1. Filter by search_name
if ($searchName !== null) {
    $filteredGames = array_filter($filteredGames, function ($game) use ($searchName) {
        // Ensure 'name' exists and is a string before calling stripos
        return isset($game['name']) && is_string($game['name']) && stripos($game['name'], $searchName) !== false;
    });
}

// 2. Filter by size_in_mb
if ($minSizeMb !== null) {
    $filteredGames = array_filter($filteredGames, function ($game) use ($minSizeMb) {
        $gameSize = getNumericValue($game, 'size_in_mb');
        return $gameSize !== null && $gameSize >= $minSizeMb;
    });
}
if ($maxSizeMb !== null) {
    $filteredGames = array_filter($filteredGames, function ($game) use ($maxSizeMb) {
        $gameSize = getNumericValue($game, 'size_in_mb');
        return $gameSize !== null && $gameSize <= $maxSizeMb;
    });
}
// Re-index array after filtering, important if elements were removed
$filteredGames = array_values($filteredGames);


// --- Sort games ---
if (!empty($filteredGames)) {
    usort($filteredGames, function ($a, $b) use ($sortBy, $sortOrder) {
        $valA = null;
        $valB = null;
        $comparison = 0;

        switch ($sortBy) {
            case 'name':
                $valA = isset($a['name']) && is_string($a['name']) ? mb_strtolower($a['name'], 'UTF-8') : '';
                $valB = isset($b['name']) && is_string($b['name']) ? mb_strtolower($b['name'], 'UTF-8') : '';
                $comparison = strcmp($valA, $valB);
                break;
            case 'size':
                $valA = getNumericValue($a, 'size_in_mb');
                $valB = getNumericValue($b, 'size_in_mb');

                if ($valA === null && $valB === null) $comparison = 0;
                elseif ($valA === null) $comparison = ($sortOrder === 'asc' ? 1 : -1);
                elseif ($valB === null) $comparison = ($sortOrder === 'asc' ? -1 : 1);
                else $comparison = $valA <=> $valB;
                break;
            case 'date':
            default:
                $valA = getNumericValue($a, 'submission_timestamp');
                $valB = getNumericValue($b, 'submission_timestamp');

                if ($valA === null && $valB === null) $comparison = 0;
                elseif ($valA === null) $comparison = ($sortOrder === 'asc' ? 1 : -1);
                elseif ($valB === null) $comparison = ($sortOrder === 'asc' ? -1 : 1);
                else $comparison = $valA <=> $valB;
                break;
        }

        return ($sortOrder === 'desc') ? -$comparison : $comparison;
    });
}

// --- Output JSON ---
echo json_encode(array_values($filteredGames), JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
?>
