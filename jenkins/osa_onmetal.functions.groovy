#!/usr/bin/env groovy


def get_onmetal_ip() {

    // Get the onmetal host IP address
    if (fileExists('hosts')) {

        String hosts = readFile("hosts")
        String ip = hosts.substring(hosts.indexOf('=')+1).replaceAll("[\n\r]", "")
        return (ip)

    } else {

        return (null)

    }

}


def onmetal_provision(datacenter_tag) {

    String ip

    try {

        // Spin onMetal Server
        echo 'Running the following playbook: build_onmetal'
        ansiblePlaybook playbook: 'build_onmetal.yaml', sudoUser: null, tags: "${datacenter_tag}"

        // Verify onMetal server data
        echo 'Running the following playbook: get_onmetal_facts'
        ansiblePlaybook inventory: 'hosts', playbook: 'get_onmetal_facts.yaml', sudoUser: null, tags: "${datacenter_tag}"
    
        // Get server IP address
        ip = get_onmetal_ip()

        // Prepare OnMetal server, retry up to 5 times for the command to work
        echo 'Running the following playbook: prepare_onmetal'
        retry(5) {
            ansiblePlaybook inventory: 'hosts', playbook: 'prepare_onmetal.yaml', sudoUser: null
        }

        // Apply CPU fix - will restart server (~5 min)
        echo 'Running the following playbook: set_onmetal_cpu'
        ansiblePlaybook inventory: 'hosts', playbook: 'set_onmetal_cpu.yaml', sudoUser: null

    } catch (err) {
        // If there is an error, tear down and re-raise the exception
        delete_onmetal(datacenter_tag)
        throw err
    }

    return (ip)

}


def vm_provision() {

    // Configure VMs onMetal server
    echo 'Running the following playbook: configure_onmetal'
    ansiblePlaybook inventory: 'hosts', playbook: 'configure_onmetal.yaml', sudoUser: null

    // Create VMs where OSA will be deployed
    echo 'Running the following playbook: create_lab'
    ansiblePlaybook inventory: 'hosts', playbook: 'create_lab.yaml', sudoUser: null

}


def vm_preparation_for_osa(release = 'stable/mitaka') {

    try {

        // Prepare each VM for OSA installation
        echo "Running the following playbook: prepare_for_osa, using the following OSA release: ${release}"
        ansiblePlaybook extras: "-e openstack_release=${release}", inventory: 'hosts', playbook: 'prepare_for_osa.yaml', sudoUser: null

    } catch (err) {
    
        // If there is an error, tear down and re-raise the exception
        delete_virtual_resources()
        //delete_onmetal(datacenter_tag)
        throw err

    } 

} 


def deploy_openstack() {
    
    try {

        echo 'Running the following playbook: deploy_osa'
        ansiblePlaybook inventory: 'hosts', playbook: 'deploy_osa.yaml', sudoUser: null

    } catch (err) {

        // If there is an error, tear down and re-raise the exception
        delete_virtual_resources()
        //delete_onmetal(datacenter_tag)
        throw err

    }

}


def upgrade_openstack(release = 'stable/mitaka') {

    // End to End Process
    // Upgrade OSA to a specific release
    echo "Running the following playbook: upgrade_osa, to upgrade to the following release: ${release}"
    ansiblePlaybook extras: "-e openstack_release=${release}", inventory: 'hosts', playbook: 'upgrade_osa.yaml', sudoUser: null  

}


def pre_project_upgrade(release = 'stable/newton', main_path = '/opt/openstack-ansible') {

   // Prepare system up to start with each project rolling upgrade
   // Tasks executed: repo, roles, and additional OSA tasks
   echo "Running the pre rolling upgrade. Going to release: ${release}"
   ansiblePlaybook extras: "-e openstack_release=${release} -e main_path=${main_path}", inventory: 'hosts', playbook: 'run_pre_upgrade.yaml', sudoUser: null

}


def upgrade_project(project, main_path = '/opt/openstack-ansible') {

   // Upgrade nova project via OSA nova role
   echo "Running ${project} project Rolling Upgrade E2E on the onMetal host"
   sh """
   cd ${main_path}/playbooks
   if [ ${project} = 'keystone' ]; then
       sudo ansible-playbook os-keystone-install.yml -i 'hosts'
   if [ ${project} = 'nova' ]; then
       sudo ansible-playbook os-nova-install.yml -i 'hosts'
   if [ ${project} = 'swift' ]; then
       sudo ansible-playbook os-swift-install.yml -i 'hosts'
   fi
   """

}


def configure_tempest() {

    String host_ip = get_onmetal_ip()

    // Install Tempest on the onMetal host
    echo 'Installing Tempest on the onMetal host'
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    git clone https://github.com/openstack/tempest.git /root/tempest
    cd /root/tempest/
    sudo pip install -r requirements.txt
    testr init
    cd /root/tempest/etc/
    wget https://raw.githubusercontent.com/osic/qa-jenkins-onmetal/master/jenkins/tempest.conf
    mkdir -p /root/subunit
    '''
    """

    // Get the tempest config file generated by the OSA deployment
    echo 'Configuring tempest based on the ansible deployment'
    sh """
    # Copy the config file from the infra utility VM to the onMetal host 
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    scp infra01_utility:/opt/tempest_*/etc/tempest.conf /root/tempest/etc/tempest.conf.osa
    ''' 
    """
   
    // Configure tempest based on the OSA deployment
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    keys="admin_password image_ref image_ref_alt uri uri_v3 public_network_id"
    for key in \$keys
    do
        a="\${key} ="
        sed -ir "s|\$a.*|\$a|g" /root/tempest/etc/tempest.conf
        b=`cat /root/tempest/etc/tempest.conf.osa | grep "\$a"`
        sed -ir "s|\$a|\$b|g" /root/tempest/etc/tempest.conf
    done
    '''
    """

}


def run_tempest_smoke_tests(results_file = 'results', elasticsearch_ip = null) {

    String host_ip = get_onmetal_ip()
    String newline = "\n"
    def tempest_output, failures

    // Run the tests and store the results in ~/subunit/before
    tempest_output = sh returnStdout: true, script: """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    cd /root/tempest/
    stream_id=`cat .testrepository/next-stream`
    ostestr --no-slowest --regex smoke
    mkdir -p /root/subunit/smoke/
    cp .testrepository/\$stream_id /root/subunit/smoke/${results_file}
    '''
    """
    // Make sure there are no failures in the smoke tests, if there are stop the workflow
    println tempest_output
    if (tempest_output.contains('- Failed:') == true) {

	failures = tempest_output.substring(tempest_output.indexOf('- Failed:') + 10)
        failures = failures.substring(0,failures.indexOf(newline)).toInteger()
        if (failures > 1) {
	    println 'Parsing failed smoke'
            if (elasticsearch_ip != null) {
	        aggregate_parse_failed_smoke(host_ip, results_file, elasticsearch_ip)
            }
            error "${failures} tests from the Tempest smoke tests failed, stopping the pipeline."
        } else {
            println 'The Tempest smoke tests were successfull.'
        }

    } else {

        error 'There was an error running the smoke tests, stopping the pipeline.'

    }
    
}


def delete_virtual_resources() {

    echo 'Running the following playbook: destroy_virtual_machines'
    ansiblePlaybook inventory: 'hosts', playbook: 'destroy_virtual_machines.yaml', sudoUser: null
    echo 'Running the following playbook: destroy_virtual_networks'
    ansiblePlaybook inventory: 'hosts', playbook: 'destroy_virtual_networks.yaml', sudoUser: null
    echo 'Running the following playbook: destroy_lab_state_file'
    ansiblePlaybook inventory: 'hosts', playbook: 'destroy_lab_state_file.yaml', sudoUser: null

}


def delete_onmetal(datacenter_tag) {

    echo 'Running the following playbook: destroy_onmetal'
    ansiblePlaybook inventory: 'hosts', playbook: 'destroy_onmetal.yaml', sudoUser: null, tags: "${datacenter_tag}"

    String host_ip = get_onmetal_ip()
    if (host_ip != null) {
        sh """
        ssh-keygen -R ${host_ip}
        """
    }

}


def install_persistent_resources_tests() {

    String host_ip = get_onmetal_ip()

    // Install Persistent Resources tests on the onMetal host
    echo 'Installing Persistent Resources Tempest Plugin on the onMetal host'
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    git clone https://github.com/osic/persistent-resources-tests.git /root/persistent-resources-tests
    pip install /root/persistent-resources-tests/
    '''
    """

}


def run_persistent_resources_tests(action = 'verify', results_file = null) {

    String host_ip = get_onmetal_ip()
    String file_name

    if (results_file == null) {
        results_file = action
    }

    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    cd /root/tempest/
    stream_id=`cat .testrepository/next-stream`
    ostestr --regex persistent-${action}
    mkdir -p /root/subunit/persistent_resources/
    cp .testrepository/\$stream_id /root/subunit/persistent_resources/${results_file}
    '''
    """

}


def install_during_upgrade_tests() {

    String host_ip = get_onmetal_ip()

    // Setup during tests
    sh """
    ssh -o StrictHostKeyChecking=no  root@${host_ip} '''
    mkdir -p /root/output
    git clone https://github.com/osic/rolling-upgrades-during-test
    cd rolling-upgrades-during-test
    pip install -r requirements.txt
    '''
    """

}


def start_during_test() {

    String host_ip = get_onmetal_ip()
    
    // Run during test
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    cd rolling-upgrades-during-test
    python call_test.py -d
    ''' &
    """ 

}


def stop_during_test() {

    String host_ip = get_onmetal_ip()

    // Stop during test by creating during.uptime.stop
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    sudo touch /usr/during.uptime.stop
    '''
    """

}


def aggregate_results(host_ip) {

    //Pull persistent, during, api, smoke results from onmetal to ES vm
    sh """
    scp -o StrictHostKeyChecking=no -r root@${host_ip}:/root/output/ /home/ubuntu/
    scp -o StrictHostKeyChecking=no -r root@${host_ip}:/root/subunit/ /home/ubuntu/
    """

}


def install_api_uptime_tests() {

    String host_ip = get_onmetal_ip()

    // setup api uptime tests
    sh """
    ssh -o StrictHostKeyChecking=no  root@${host_ip} '''
    mkdir -p /root/output
    git clone https://github.com/osic/api_uptime.git
    cd api_uptime
    pip install -r requirements.txt
    '''
    """

}


def start_api_uptime_tests() {

    String host_ip = get_onmetal_ip()
	
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    sudo rm -f /usr/api.uptime.stop
    cd api_uptime/api_uptime
    python call_test.py -v -d -s nova,swift -o /root/output/api.uptime.out
    ''' &
    """

}


def stop_api_uptime_tests() {

    String host_ip = get_onmetal_ip()

    // stop the API uptime tests
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    sudo touch /usr/api.uptime.stop
    '''
    """

}

def setup_parse_persistent_resources(){
    
    String host_ip = get_onmetal_ip()
    
    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    git clone https://github.com/osic/persistent-resources-tests-parse.git /root/persistent-resources-tests-parse
    pip install /root/persistent-resources-tests-parse/
    '''
    """

}

def parse_persistent_resources_tests(){
    
    String host_ip = get_onmetal_ip()

    sh """
    ssh -o StrictHostKeyChecking=no root@${host_ip} '''
    cd /root/subunit/persistent_resources/
    resource-parse --u . > /root/output/persistent_resource.txt
    rm *.csv
    '''
    """

}

def aggregate_parse_failed_smoke(host_ip, results_file, elasticsearch_ip) {

    //Pull persistent, during, api, smoke results from onmetal to ES vm
    sh """
    ssh -o StrictHostKeyChecking=no ubuntu@${elasticsearch_ip} '''
    scp -o StrictHostKeyChecking=no -r root@${host_ip}:/root/output/ /home/ubuntu/
    scp -o StrictHostKeyChecking=no -r root@${host_ip}:/root/subunit/ /home/ubuntu/
    git clone https://github.com/osic/elastic-benchmark
    sudo pip install -e elastic-benchmark
    '''
    """

    if (results_file == 'after_upgrade') {
        //Pull persistent, during, api, smoke results from onmetal to ES 
        sh """
        ssh -o StrictHostKeyChecking=no ubuntu@${elasticsearch_ip} '''
        elastic-upgrade -u /home/ubuntu/output/api.uptime.out -d /home/ubuntu/output/during.uptime.out -p /home/ubuntu/output/persistent_resource.txt -b /home/ubuntu/subunit/smoke/before_upgrade -a /home/ubuntu/subunit/smoke/after_upgrade
        ''''
        """
    }
    else {
        sh """
        ssh -o StrictHostKeyChecking=no ubuntu@${elasticsearch_ip} '''
        elastic-upgrade -b /home/ubuntu/subunit/smoke/before_upgrade
        '''
        """
    }

}

def install_elastic_parser() {
	
    sh """
    rm -rf elastic-benchmark
    git clone https://github.com/osic/elastic-benchmark
    sudo pip install -e elastic-benchmark
    """"

}


def parse_results() {
	
    sh """
    elastic-upgrade -u /home/ubuntu/output/api.uptime.out -d /home/ubuntu/output/during.uptime.out -p /home/ubuntu/output/persistent_resource.txt -b /home/ubuntu/subunit/smoke/before_upgrade -a /home/ubuntu/subunit/smoke/after_upgrade
    elastic-upgrade -s /home/ubuntu/output/nova_status.txt
    elastic-upgrade -s /home/ubuntu/output/swift_status.txt
    elastic-upgrade -s /home/ubuntu/output/keystone_status.txt
    """

}


// The external code must return it's contents as an object
return this;
