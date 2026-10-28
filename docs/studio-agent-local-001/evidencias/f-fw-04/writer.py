import sys, time
from pathlib import Path
from nekkora_rpa.core.checkpoints import CheckpointRecord, CheckpointStore, CheckpointWriteFailed
from nekkora_rpa.core.contracts import TransactionStatus
p = Path(sys.argv[1]); s = CheckpointStore(p, "exp"); fails = 0; n = 0
end = time.time() + float(sys.argv[2])
while time.time() < end:
    n += 1
    try:
        s.put("tx-%020x" % (n % 50), CheckpointRecord(status=TransactionStatus.SUCCEEDED, attempts=1))
    except CheckpointWriteFailed:
        fails += 1
print(f"escritas={n} falhas={fails}")
