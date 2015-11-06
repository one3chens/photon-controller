# Configurations
seed 10000
# log /tmp/psim.log DEBUG

# Configure simulator enviroment
load_flavors common/vm.yml
load_flavors common/ephemeral-disk.yml
load_datastores common/datastores.yml

# load schedulers and hosts
load_tree negative_constraints/schedulers.yml

# Execute simulator
run negative_constraints/requests.yml

# Results
print_results
check_results negative_constraints/results.yml
