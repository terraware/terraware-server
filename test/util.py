from contextlib import contextmanager
from http import HTTPStatus
from typing import Dict, Optional

import pytest
from requests import HTTPError


def flatten_dict(original: Dict) -> Dict:
    def add_to_flattened_dict(source: Dict, prefix: str, dest: Dict) -> Dict:
        for key, value in source.items():
            if type(value) == dict:
                add_to_flattened_dict(value, f"{prefix}{key}.", dest)
            else:
                dest[f"{prefix}{key}"] = value
        return dest

    return add_to_flattened_dict(original, "", {})


@contextmanager
def expect_error(
    status: HTTPStatus = HTTPStatus.BAD_REQUEST, message: Optional[str] = None
):
    with pytest.raises(HTTPError) as exc_info:
        yield exc_info

    assert exc_info.value.response.status_code == status.value
    payload = exc_info.value.response.json()
    assert payload["status"] == "error"
    if message:
        assert payload["error"]["message"] == message
