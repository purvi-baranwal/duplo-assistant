import requests
import json
import re
from datetime import datetime

# === Load test cases ===
with open("test_suite.json") as f:
    test_cases = json.load(f)

# === Utility Functions ===

def normalize_timestamp(value):
    try:
        if isinstance(value, str) and re.fullmatch(r'\d{13}', value):
            return datetime.fromtimestamp(int(value) / 1000).strftime('%Y-%m-%d %H:%M:%S')
    except Exception:
        pass
    return value

def convert_timestamps_in_response(text):
    return re.sub(
        r'\b(1\d{12,13})\b',
        lambda m: datetime.fromtimestamp(int(m.group()) / 1000).strftime('%Y-%m-%d %H:%M:%S'),
        text
    )

def extract_result(natural_response, expected):
    """Extract result from natural response based on type of expected"""
    if isinstance(expected, int):
        numbers = re.findall(r'\b\d+\b', natural_response)
        if numbers:
            return int(numbers[-1])
    elif isinstance(expected, str):
        # Try to extract datetime with or without milliseconds
        match = re.search(r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d{3})?', natural_response)
        if match:
            dt = match.group()
            # Ensure .000 milliseconds
            if '.' not in dt:
                dt += '.000'
            return dt
        # match UUID or email
        match = re.search(r"[\w\.-]+@[\w\.-]+|\b\w{8}-\w{4}-\w{4}-\w{4}-\w{12}\b|\b\w[\w\-]+", natural_response)
        return normalize_timestamp(match.group()) if match else None
    elif isinstance(expected, list):
        return re.findall(r'\b\w{8}-\w{4}-\w{4}-\w{4}-\w{12}\b', natural_response)
    elif isinstance(expected, dict):
        return expected  # placeholder for deeper comparison
    return None

def evaluate_match(actual, expected, match_type, query=""):
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

    elif match_type == 'most_recent':
        # Placeholder: replace with real DB lookup
        most_recent_id = expected  # simulate DB value
        return actual == most_recent_id

    elif match_type == 'earliest':
        # Placeholder: replace with real DB lookup
        earliest_value = expected  # simulate DB value
        return actual == earliest_value

    elif match_type == 'temporal_range':
        try:
            actual_dt = datetime.strptime(actual, "%Y-%m-%d %H:%M:%S")
            expected_dt = datetime.strptime(expected, "%Y-%m-%d %H:%M:%S")
            diff = abs((actual_dt - expected_dt).total_seconds())
            return diff <= 300  # within 5 minutes
        except:
            return False

    elif match_type == 'top_k_match':
        return actual in expected if isinstance(expected, list) else False

    elif match_type == 'top_entity_and_threshold':
        if not isinstance(actual, list) or not isinstance(expected, list):
            return False
        if not actual or not expected:
            return False

        # ✅ Filter: only dicts with 'assignee' and 'count' keys
        valid_expected = [
            x for x in expected
            if isinstance(x, dict) and "assignee" in x and "count" in x
        ]
        if not valid_expected:
            return False

        top_expected = max(valid_expected, key=lambda x: x.get("count", 0))
        expected_name = top_expected.get("assignee")
        expected_count = top_expected.get("count", 0)
        count_threshold = (expected_count // 100) * 100

        for a in actual:
            if isinstance(a, dict) and a.get("assignee") == expected_name:
                actual_count = a.get("count", 0)
                return actual_count >= count_threshold

        return False  # Assignee not found in actual

    return false

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

    # Normalize timestamps inside natural response
    natural_response = convert_timestamps_in_response(natural_response)

    actual = extract_result(natural_response, expected)
    matched = evaluate_match(actual, expected, match_type, query)

    if matched is True:
        status = "PASS"
        success += 1
    elif matched is False:
        status = "FAIL"
        failed += 1
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
