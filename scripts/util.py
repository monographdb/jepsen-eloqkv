import re
import subprocess
import sys
import os
from datetime import datetime
import logging
import smtplib
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart
from dotenv import load_dotenv
import os

# Load environment variables from the .env file
load_dotenv()


def setup_logging():
    # Create a logger
    logger = logging.getLogger("run_and_check")
    logger.setLevel(logging.DEBUG)

    # Create a console handler
    console_handler = logging.StreamHandler()
    console_handler.setLevel(logging.DEBUG)

    # Create a file handler
    file_handler = logging.FileHandler("run_and_check.log")
    file_handler.setLevel(logging.DEBUG)

    # Create a log formatter
    formatter = logging.Formatter(
        "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
    )
    console_handler.setFormatter(formatter)
    file_handler.setFormatter(formatter)

    # Add handlers to the logger
    logger.addHandler(console_handler)
    logger.addHandler(file_handler)

    return logger


logger = setup_logging()


# Function to run a command and capture its output
def run_command(command, timeout=None):
    logger.info(f"run command {command}")
    result = subprocess.run(
        command, shell=True, capture_output=True, text=True, check=True, timeout=timeout
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
        logger.info(f"Host: {host}, PID: {pid}")

        # Reset iptables and resume the process
        try:
            run_command(f"ssh {host} 'sudo iptables -F'")
            run_command(f"ssh {host} 'kill -CONT {pid}'")
        except subprocess.CalledProcessError as e:
            logger.warning(f"Failed to execute command on {host}: {e}")


def start_eloqkv_cluster():
    run_command("eloqctl start eloqkv-cluster")


def run_jepsen_test():
    try:
        with open("scripts/jepsen_cmd.sh", "r") as file:
            content = file.read()
            logger.info(f"jepsen command: {content}")

        _, return_code = run_command("bash scripts/jepsen_cmd.sh")
        logger.info(f"jepsen return code:{return_code}")
        return return_code

    except subprocess.CalledProcessError as e:
        logger.warning(f"Error occurred: {e.stderr}")
        return 1


# Define the command and arguments
redis_command = "redis-cli -h {node} -p 6389 {command}"
node_list = ["store-1", "store-2", "compute-6"]
log_node = "compute-5"
rsync_command = "rsync -azL '{source_dir}' '{destination_dir}'"
rsync_remote_command = "rsync -azL -e ssh eloq@{node}:{source_dir} {destination_dir}"
flushdb_command = redis_command.format(node=node_list[0], command="flushdb")
rsync_super_server_command = "rsync -azL -e ssh error_log eloq@192.168.1.59:~/"


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
    run_command(rsync_super_server_command)
    run_command(
        rsync_command.format(
            source_dir="/home/eloq/eloqkv-cluster/LogServer/logs/g0n0/log-service.log.INFO",
            destination_dir=root_dir,
        )
    )
    remote_root_dir = f"~/{root_dir}"
    send_email(
        f"Detect failures related to Jepsen test or Eloqkv crash. Please refer eloq@192.168.1.59:{remote_root_dir} for more details."
    )


def flushdb():
    try:
        run_command(flushdb_command, timeout=60)
    except subprocess.TimeoutExpired as e:
        logger.warning(f"Flushdb command timed out: {e}")
        save_error_log()
        sys.exit(1)
    except subprocess.CalledProcessError as e:
        logger.warning(f"Error occurred: {e.stderr}")
        return 1


def check_client_num():
    try:
        for node in node_list:
            result, _ = run_command(redis_command.format(node=node, command="info"))

            # Filter output for "connected_clients"
            for line in result.splitlines():
                if "connected_clients" in line:
                    logger.info(line)  # Print or process the matching line
                    client_num = int(line.split(":")[1])

                    if client_num > 1:
                        logger.warning(
                            "There are unclosed transactions in EloqKV. Please check if any other redis client is connecting."
                        )
                    break

    except subprocess.CalledProcessError as e:
        logger.warning(f"Error occurred: {e.stderr}")


def check_stdout_log():
    root_dir = "tmp_stdout"
    os.makedirs(root_dir, exist_ok=True)

    for node in node_list:
        node_dir = os.path.join(root_dir, node)
        os.makedirs(node_dir, exist_ok=True)
        stdout_file = os.path.join(node_dir, "std-out-6389")
        run_command(
            rsync_remote_command.format(
                node=node,
                source_dir="~/eloqkv-cluster/EloqKV/logs/std-out-6389",
                destination_dir=stdout_file,
            )
        )
        if not check_log_for_errors(stdout_file):
            return False
    return True


def check_log_for_errors(log_file):

    # Open the log file
    try:
        with open(log_file, "r") as f:
            lines = f.readlines()[-50:]
    except FileNotFoundError:
        logger.warning(f"Log file {log_file} not found.")
        return False
    except Exception as e:
        logger.warning(f"Error reading log file: {e}")
        return False

    # Define the list of error keywords to search for
    error_keywords = [
        # "Assertion",
        "assert",
        "asan",
        "AddressSanitizer",
        "Shadow byte",
        "core dumped",
        "Segmentation fault",
    ]

    # Check the log content for any of the defined error keywords
    for line in lines:
        for keyword in error_keywords:
            if keyword.lower() in line.lower():  # Case-insensitive search
                logger.error(f"Found error in log: {line.strip()}")
                return False  # Return after finding the first matching error

    logger.info("No errors found in the last 50 lines of the log.")
    return True


def send_email(content):
    logger.info("Send email start.")
    smtp_server = "smtp.qq.com"
    smtp_port = 465
    sender_email = os.getenv("SENDER_EMAIL")
    receiver_email = os.getenv("RECEIVER_EMAIL").split(",")
    password = os.getenv("PASSWORD")
    logger.info(f"sender: {sender_email}")

    # create email
    msg = MIMEMultipart()
    msg["From"] = sender_email
    msg["To"] = ", ".join(receiver_email)
    msg["Subject"] = "Jepsen test fail!"

    # content
    # body = "This is a test email sent from Python using QQ Mail SMTP server."
    msg.attach(MIMEText(content, "plain"))

    try:
        with smtplib.SMTP_SSL(smtp_server, smtp_port) as server:
            server.login(sender_email, password)
            server.sendmail(sender_email, receiver_email, msg.as_string())
            logger.info("Email sent successfully.")
    except Exception as e:
        logger.warning(f"Error: {e}")
