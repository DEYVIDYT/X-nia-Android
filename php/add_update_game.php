<?php
// Set headers
header("Content-Type: application/json; charset=UTF-8");
header("Access-Control-Allow-Origin: *"); // Consider restricting this in production
header("Access-Control-Allow-Methods: POST");
header("Access-Control-Allow-Headers: Content-Type, Access-Control-Allow-Headers, Authorization, X-Requested-With");

// Define the path to the JSON file reliably
$jsonFile = __DIR__ . "/games.json";

// Include or define the parseSizeToMB function
// Assuming parseSizeToMB.php is in the same directory or adjust path as needed
if (file_exists(__DIR__ . '/parseSizeToMB.php')) {
    require_once __DIR__ . '/parseSizeToMB.php';
} else {
    // Fallback or error if the function file is missing
    // For this example, we'll define it here if not found,
    // but using require_once is cleaner if it's in a separate file.
    if (!function_exists('parseSizeToMB')) {
        /**
         * Parses a size string (e.g., "1.2GB", "500 MB", "700kb", "14,5MB") into Megabytes (MB).
         * @param string|null $sizeStr The size string to parse.
         * @return float|null The size in MB as a float, or null if parsing fails.
         */
        function parseSizeToMB($sizeStr) {
            if ($sizeStr === null || trim($sizeStr) === '') { return null; }
            $normalizedStr = strtoupper(trim($sizeStr));
            $normalizedStr = str_replace(',', '.', $normalizedStr);
            if (preg_match('/^([0-9\.]+)\s*(GB|MB|KB)?$/', $normalizedStr, $matches)) {
                $value = floatval($matches[1]);
                $unit = isset($matches[2]) ? $matches[2] : 'MB';
                switch ($unit) {
                    case 'GB': return $value * 1024;
                    case 'KB': return $value / 1024;
                    case 'MB': default: return $value;
                }
            } else if (is_numeric($normalizedStr)) {
                return floatval($normalizedStr);
            }
            return null;
        }
    }
}


$response = ['success' => false, 'message' => 'Invalid request.'];

// Check if it's a POST request
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    // Get the raw POST data
    $rawData = file_get_contents('php://input');
    // Attempt to decode the JSON data
    $inputData = json_decode($rawData, true);

    // Validate input data
    if (isset($inputData['name'], $inputData['url'], $inputData['size'], $inputData['source'])) {
        $name = trim($inputData['name']);
        $url = trim($inputData['url']);
        $originalSizeStr = trim($inputData['size']);
        $source = trim($inputData['source']);

        if (empty($name) || empty($url) || empty($originalSizeStr) || empty($source)) {
            $response['message'] = 'Dados incompletos. Nome, URL, tamanho e origem são obrigatórios.';
        } else {
            $games = [];
            // Load existing games if the file exists and is valid
            if (file_exists($jsonFile)) {
                $currentJsonData = file_get_contents($jsonFile);
                if ($currentJsonData !== false) {
                    $decodedGames = json_decode($currentJsonData, true);
                    if (is_array($decodedGames)) {
                        $games = $decodedGames;
                    }
                }
            }

            // Create new game entry
            $newGame = [
                'id' => uniqid('game_', true), // Generate a unique ID
                'name' => $name,
                'url' => $url,
                'original_size_string' => $originalSizeStr,
                'size_in_mb' => parseSizeToMB($originalSizeStr), // Parse the size
                'source' => $source,
                'submission_timestamp' => time() // Current Unix timestamp
            ];

            // Prepend the new game to the array (newest first)
            array_unshift($games, $newGame);

            // Save the updated games array back to the JSON file
            // JSON_UNESCAPED_SLASHES to prevent URLs like https:\/\/...
            // JSON_UNESCAPED_UNICODE to keep Unicode characters as they are (e.g., in game names)
            if (file_put_contents($jsonFile, json_encode($games, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE), LOCK_EX) !== false) {
                $response = ['success' => true, 'message' => 'Jogo adicionado com sucesso!'];
            } else {
                $response['message'] = 'Erro ao salvar os dados do jogo no servidor.';
                // Potentially log server-side error here
                error_log("Failed to write to games.json");
            }
        }
    } else {
        $response['message'] = 'Dados JSON inválidos ou campos obrigatórios ausentes (name, url, size, source).';
    }
} else {
    $response['message'] = 'Método de requisição inválido. Apenas POST é permitido.';
}

// Send the JSON response back to the client
echo json_encode($response);
?>
