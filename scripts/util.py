import re
import subprocess
import sys
import os
from datetime import datetime


# Function to run a command and capture its output
def run_command(command):
    print(command)
    result = subprocess.run(
        command, shell=True, capture_output=True, text=True, check=True
    )
    return result.stdout, result.returncode


def recover():
    # Save the output of the eloqctl status eloqkv-cluster command to a variable
    status_output, _ = run_command("eloqctl status eloqkv-cluster")

    # Extract hosts and PIDs using regular expressions
    hosts = re.findall(r"host=([^,]+)", status_output)  # Extract host IP addresses
    pids = [
        int(pid) for pid in re.findall(r"pid: (\d+)", status_output)
    ]  # Extract PIDs

    # Create a dictionary to map hosts to their corresponding PIDs
    host_pid = dict(zip(hosts, pids))

    # Iterate through the host-PID pairs
    for host, pid in host_pid.items():
        print(f"Host: {host}, PID: {pid}")

        # Reset iptables and resume the process
        try:
            run_command(f"ssh {host} 'sudo iptables -F'")
            run_command(f"ssh {host} 'kill -CONT {pid}'")
        except subprocess.CalledProcessError as e:
            print(f"Failed to execute command on {host}: {e}")


def start_eloqkv_cluster():
    run_command("eloqctl start eloqkv-cluster")


def run_jepsen_test():
    try:
        _, return_code = run_command(
            "lein run test-all --node compute-6 --node store-1 --node store-2 --username eloq --password eloq --time-limit 6000  --nemesis none  --workload append  --nemesis-interval 20 --max-writes-per-key 16 --max-txn-length 4 --test-count 1"
        )
        return return_code

    except subprocess.CalledProcessError as e:
        print(f"Error occurred: {e.stderr}")
        return 1


# Define the command and arguments
redis_command = "redis-cli -h {node} -p 6389 {command}"
node_list = ["store-1", "store-2", "compute-6"]
rsync_command = "rsync -azL '{source_dir}' '{destination_dir}'"
rsync_remote_command = "rsync -azL -e ssh eloq@{node}:{source_dir} {destination_dir}"
flushdb_command = redis_command.format(node=node_list[0], command="flushdb")


def save_error_log():
    jepsen_source_dir = run_command("readlink -f store/current")[0].strip()
    current_time = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    root_dir = f"error_log/{current_time}"
    os.makedirs(root_dir, exist_ok=True)

    # rsync
    jepsen_destination_dir = os.path.join(root_dir, "jepsen")
    run_command(
        rsync_command.format(
            source_dir=jepsen_source_dir + "/", destination_dir=jepsen_destination_dir
        )
    )

    for node in node_list:
        node_destination_dir = os.path.join(root_dir, node)
        os.makedirs(node_destination_dir, exist_ok=True)
        run_command(
            rsync_remote_command.format(
                node=node,
                source_dir="~/eloqkv-cluster/EloqKV/logs/tx-6389/eloqkv.log.INFO",
                destination_dir=os.path.join(node_destination_dir, "eloqkv.log.INFO"),
            )
        )
        run_command(
            rsync_remote_command.format(
                node=node,
                source_dir="~/eloqkv-cluster/EloqKV/logs/std-out-6389",
                destination_dir=os.path.join(node_destination_dir, "std-out-6389"),
            )
        )


def check_client_num():
    try:
        for node in node_list:
            result, _ = run_command(redis_command.format(node=node, command="info"))

            # Filter output for "connected_clients"
            for line in result.splitlines():
                if "connected_clients" in line:
                    print(line)  # Print or process the matching line
                    client_num = int(line.split(":")[1])

                    try:
                        assert client_num == 1, "There are unclosed txm in EloqKV."
                    except AssertionError as e:
                        # If assertion fails, execute the specified command
                        print(e)

                        save_error_log()

                        sys.exit(1)
        result = run_command(flushdb_command)
        return True

    except subprocess.CalledProcessError as e:
        print(f"Error occurred: {e.stderr}")
        return False
