from util import (
    recover,
    save_error_log,
    start_eloqkv_cluster,
    run_jepsen_test,
    check_client_num,
)

for i in range(1, 100):
    recover()
    start_eloqkv_cluster()
    if run_jepsen_test() != 0:
        save_error_log()
    check_client_num()
