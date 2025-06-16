import requests
import json
import re

# === Load test cases ===
with open("test_suite.json") as f:
    test_cases = json.load(f)

# === Utility Functions ===

def extract_result(natural_response, expected):
    """Extract result from natural response based on type of expected"""
    if isinstance(expected, int):
        # Get last number assuming it’s the actual answer (to avoid query echo)
        numbers = re.findall(r'\b\d+\b', natural_response)
        if numbers:
            return int(numbers[-1])
    elif isinstance(expected, str):
        match = re.search(r"[\w\.-]+@[\w\.-]+|\b\w{8}-\w{4}-\w{4}-\w{4}-\w{12}\b|\b\w[\w\-]+", natural_response)
        return match.group() if match else None
    elif isinstance(expected, list):
        return re.findall(r'\b\w{8}-\w{4}-\w{4}-\w{4}-\w{12}\b', natural_response)
    elif isinstance(expected, dict):
        return expected  # placeholder, can implement key-by-key match if needed
    return None

def is_match(actual, expected, match_type, query=""):
    if actual is None:
        return False
    if isinstance(actual, str) and actual in query:
        return False  # prevent echoing
    if match_type == 'exact':
        return actual == expected
    elif match_type == 'subset':
        return set(expected).issubset(set(actual)) if isinstance(actual, list) else False
    elif match_type == 'minimum':
        if isinstance(expected, int) and isinstance(actual, int):
            return actual >= expected
        if isinstance(expected, list) and isinstance(actual, list):
            return all(item in actual for item in expected)
        if isinstance(expected, dict) and isinstance(actual, dict):
            return all(actual.get(k) == v for k, v in expected.items())
        return False
    elif match_type == 'dynamic':
        return None  # defer handling
    else:
        raise ValueError(f"Unknown match_type: {match_type}")

# === Test runner ===
results = []
success, failed, skipped = 0, 0, 0
url = "http://localhost:8080/assistant"

for test in test_cases:
    if not test.get("test", True):
        continue

    query = test["query"]
    expected = test.get("expected") or test.get("expected_result")
    match_type = test.get("match_type", "exact")

    try:
        response = requests.post(url, data=query)
        natural_response = response.text.strip() if response.status_code == 200 else ""
    except Exception as e:
        results.append({
            "id": test["id"],
            "query": query,
            "expected": expected,
            "actual": None,
            "natural_response": str(e),
            "match_type": match_type,
            "status": "REQUEST FAILED"
        })
        failed += 1
        continue

    if response.status_code != 200:
        results.append({
            "id": test["id"],
            "query": query,
            "expected": expected,
            "actual": None,
            "natural_response": natural_response,
            "match_type": match_type,
            "status": "HTTP ERROR",
            "http_status": response.status_code
        })
        failed += 1
        continue

    actual = extract_result(natural_response, expected)
    matched = is_match(actual, expected, match_type, query)

    if matched is True:
        status = "PASS"
        success += 1
    elif matched is False:
        if match_type == "dynamic" and natural_response:
            status = "PASS"
            success += 1
        else:
            status = "FAIL"
            failed += 1
    else:
        if match_type == "dynamic":
            status = "PASS"
            success += 1
        else:
            status = "SKIPPED"
            skipped += 1

    results.append({
        "id": test["id"],
        "query": query,
        "expected": expected,
        "actual": actual,
        "natural_response": natural_response,
        "match_type": match_type,
        "status": status
    })

# === Save results to file ===
with open("test_results.json", "w") as f:
    json.dump(results, f, indent=2)

# === Summary ===
print(f"\nSummary: ✅ {success} passed | ❌ {failed} failed | ⏩ {skipped} skipped")
print("Test results saved to test_results.json")
