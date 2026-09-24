import pytest
from pydantic import ValidationError
from backend.models import Task, Place


@pytest.mark.parametrize("kind", ["PARCEL", "TAKEOUT"])
def test_pickup_requires_real_location(kind):
    with pytest.raises(ValidationError, match="实际领取地点"):
        Task(id="pickup", title="取件", place_type=kind)


def test_pickup_can_use_fixed_location():
    point = Place(id="pickup", name="指定领取点", longitude=121, latitude=31)
    assert Task(id="pickup", title="取件", place=point).place == point
