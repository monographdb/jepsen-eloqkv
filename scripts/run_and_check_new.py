from util import (
    recover,
    save_error_log,
    start_eloqkv_cluster,
    run_jepsen_test,
    check_client_num,
    flushdb,
    check_stdout_log,
)

for i in range(1, 100):
    recover()
    start_eloqkv_cluster()
    flushdb()
    if (not check_stdout_log()) or (run_jepsen_test() != 0):
        save_error_log()
        break

    check_client_num()
    if not check_stdout_log():
        save_error_log()
        break

recover()
