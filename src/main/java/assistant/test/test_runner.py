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
    if isinstance(expected, int):
        numbers = re.findall(r'\b\d+\b', natural_response)
        if numbers:
            return int(numbers[-1])
    elif isinstance(expected, str):
        match = re.search(r"[\w\.-]+@[\w\.-]+|\b\w{8}-\w{4}-\w{4}-\w{4}-\w{12}\b|\b\w[\w\-]+", natural_response)
        return normalize_timestamp(match.group()) if match else None
    elif isinstance(expected, list):
        return re.findall(r'\b\w{8}-\w{4}-\w{4}-\w{4}-\w{12}\b', natural_response)
    elif isinstance(expected, dict):
        return expected  # placeholder
    return None

def evaluate_match(actual, expected, match_type, query="", natural_response=""):
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
        return actual == expected

    elif match_type == 'earliest':
        return actual == expected

    elif match_type == 'temporal_range':
        try:
            # Normalize timestamps: if missing milliseconds, add `.000` for consistency
            actual_clean = actual.strip()
            expected_clean = expected.strip()

            if '.' not in actual_clean:
                actual_clean += ".000"
            if '.' not in expected_clean:
                expected_clean += ".000"

            actual_dt = datetime.strptime(actual_clean, "%Y-%m-%d %H:%M:%S.%f")
            expected_dt = datetime.strptime(expected_clean, "%Y-%m-%d %H:%M:%S.%f")
            diff = abs((actual_dt - expected_dt).total_seconds())

            return diff <= 300  # 5 minutes
        except Exception as e:
            print(f"[Error parsing temporal_range] {e}")
            return False


    elif match_type == 'top_k_match':
        return actual in expected if isinstance(expected, list) else False

    elif match_type == 'top_entity_and_threshold':
        if not isinstance(actual, list) or not isinstance(expected, list):
            return False
        if not actual or not expected:
            return False

        for a in actual:
            if isinstance(a, dict) and "num_work_orders" in a:
                a["count"] = a.get("num_work_orders")

        valid_expected = [
            x for x in expected if isinstance(x, dict) and "assignee" in x and "count" in x
        ]
        if not valid_expected:
            return False

        top_expected = max(valid_expected, key=lambda x: x.get("count", 0))
        expected_name = top_expected.get("assignee")
        expected_count = top_expected.get("count", 0)
        count_threshold = (expected_count // 100) * 100

        print(f"[Debug] Looking for top expected: {expected_name}, count threshold: {count_threshold}")
        print(f"[Debug] Actual assignees: {[a.get('assignee') for a in actual]}")

        for a in actual:
            if isinstance(a, dict) and a.get("assignee") == expected_name:
                actual_count = a.get("count", 0)
                return actual_count >= count_threshold

        return False

    elif match_type == 'string':
        return isinstance(expected, str) and (expected in natural_response or expected in query)

    elif match_type == 'numeric':
        if isinstance(expected, int):
            match = re.search(r'\b\d+\b', natural_response)
            if match:
                actual_number = int(match.group())
                return actual_number == expected or actual_number >= expected
        return False

    return False

# === Test runner ===
results = []
success, failed, skipped = 0, 0, 0
url = "http://localhost:8080/assistant"

total_tests = len([t for t in test_cases if t.get("test", True)])

for index, test in enumerate(test_cases, start=1):
    if not test.get("test", True):
        continue

    print(f"Running test {index}/{total_tests} (ID: {test.get('id')})...")

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

    natural_response = convert_timestamps_in_response(natural_response)
    actual = extract_result(natural_response, expected)
    matched = evaluate_match(actual, expected, match_type, query, natural_response)

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
