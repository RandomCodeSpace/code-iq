from django.db import models


class Subscriber(models.Model):
    """A downstream subscriber that receives notifications."""

    email = models.CharField(max_length=255)

    class Meta:
        db_table = "subscribers"
