import pytest


@pytest.fixture(autouse=True)
def no_real_network_lookups(monkeypatch):
    """Tests never call RDAP; domain-age tests inject their own fake."""
    monkeypatch.setenv("DOMAIN_AGE", "0")
    from app import domain_age
    domain_age._cache.clear()
    domain_age._bootstrap.update(at=0.0, map={})


@pytest.fixture(autouse=True)
def no_real_llm(monkeypatch):
    """Tests never call OpenRouter, even if the developer's shell has the key exported."""
    for k in ("OPENROUTER_API_KEY", "LLM_MODEL"):
        monkeypatch.delenv(k, raising=False)
    from app import llm
    llm._cache.clear()
    llm._inflight.clear()


@pytest.fixture(autouse=True)
def fresh_cost_guard():
    from app import costguard
    costguard.reset_all()
    yield
    costguard.reset_all()
