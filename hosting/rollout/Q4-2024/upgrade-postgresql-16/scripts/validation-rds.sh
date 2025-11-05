#!/bin/bash

END='\033[00m'
YELLOW='\033[01;33m'
BOLD='\033[1m'
UNDERLINE='\033[4m'

# PostgreSQL connection details
# OLD_HOST=""
# NEW_HOST=""
PORT="5432"

# Function to run SQL and return result
run_sql() {
    local host=$1
    local sql=$2
    local db=${3:-postgres}
    PGPASSWORD=$PGPASSWORD PGUSER=$PGUSER psql -h "$host" -p "$PORT" -d "$db" -t -c "$sql" | sort
}

run_sql_new() {
    local host=$1
    local sql=$2
    local db=${3:-postgres}
    PGPASSWORD=$PGPASSWORD_NEW PGUSER=$PGUSER_NEW psql -h "$host" -p "$PORT" -d "$db" -t -c "$sql" | sort
}

# Compare number of databases
compare_databases() {
    echo -e "${YELLOW}${UNDERLINE}Comparing number of databases...${END}${END}"
    local old_dbs=$(run_sql $OLD_HOST "SELECT datname FROM pg_database WHERE datistemplate = false;")
    local new_dbs=$(run_sql_new $NEW_HOST "SELECT datname FROM pg_database WHERE datistemplate = false;")
    echo "Old instance databases: $old_dbs"
    echo "New instance databases: $new_dbs"
    if [ "$old_dbs" == "$new_dbs" ]; then
        echo -e "\e[32mMATCH\e[0m Database list matches"
    else
        echo -e "\e[31mMISMATCH\e[0m Database list mismatch"
        echo "Only in old instance: $(comm -23 <(echo "$old_dbs" | tr ' ' '\n' | sort) <(echo "$new_dbs" | tr ' ' '\n' | sort))"
        echo "Only in new instance: $(comm -13 <(echo "$old_dbs" | tr ' ' '\n' | sort) <(echo "$new_dbs" | tr ' ' '\n' | sort))"
        echo "Exiting due to databases mismatch..."
        exit 1

    fi
}

# Compare tables and rows for each database
compare_tables_and_rows() {
    echo "Comparing tables and rows for each database..."
    local dbs=$(run_sql $OLD_HOST "SELECT datname FROM pg_database WHERE datistemplate = false;")
    
    for db in $dbs; do
        echo "Database: $db"
        local old_tables=$(run_sql $OLD_HOST "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public';" "$db")
        local new_tables=$(run_sql_new $NEW_HOST "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public';" "$db")
        
        if [ "$old_tables" == "$new_tables" ]; then
            echo -e "  \e[32mMATCH\e[0m Table list matches"
        else
            echo -e "  \e[31mMISMATCH\e[0m Table list mismatch"
            echo "  Only in old instance: $(comm -23 <(echo "$old_tables" | tr ' ' '\n' | sort) <(echo "$new_tables" | tr ' ' '\n' | sort))"
            echo "  Only in new instance: $(comm -13 <(echo "$old_tables" | tr ' ' '\n' | sort) <(echo "$new_tables" | tr ' ' '\n' | sort))"
            echo "Exiting due to tables mismatch..."
            exit 1

        fi
        
        for table in $old_tables $new_tables; do
            local old_rows=$(run_sql $OLD_HOST "SELECT COUNT(*) FROM \"$table\";" "$db")
            local new_rows=$(run_sql_new $NEW_HOST "SELECT COUNT(*) FROM \"$table\";" "$db")
            if [ "$old_rows" == "$new_rows" ]; then
                echo -e "  \e[32mMATCH\e[0m $table: Row count matches ($old_rows rows)"
            else
                echo -e "  \e[31mMISMATCH\e[0m $table: Row count mismatch - Old: $old_rows, New: $new_rows"
            fi
        done
    done
}

# Compare indexes
compare_indexes() {
    echo "Comparing indexes..."
    local dbs=$(run_sql $OLD_HOST "SELECT datname FROM pg_database WHERE datistemplate = false;")
    
    for db in $dbs; do
        echo "Database: $db"
        local old_indexes=$(run_sql $OLD_HOST "SELECT indexname FROM pg_indexes WHERE schemaname = 'public';" "$db")
        local new_indexes=$(run_sql_new $NEW_HOST "SELECT indexname FROM pg_indexes WHERE schemaname = 'public';" "$db")
        
        if [ "$old_indexes" == "$new_indexes" ]; then
            echo -e "  \e[32mMATCH\e[0m Index list matches"
        else
            echo -e "  \e[31mMISMATCH\e[0m Index list mismatch"
            echo "  Only in old instance: $(comm -23 <(echo "$old_indexes" | tr ' ' '\n' | sort) <(echo "$new_indexes" | tr ' ' '\n' | sort))"
            echo "  Only in new instance: $(comm -13 <(echo "$old_indexes" | tr ' ' '\n' | sort) <(echo "$new_indexes" | tr ' ' '\n' | sort))"
            echo "Exiting due to indexes mismatch..."
            exit 1
        fi
    done
}

# Compare functions
compare_functions() {
    echo "Comparing functions..."
    local dbs=$(run_sql $OLD_HOST "SELECT datname FROM pg_database WHERE datistemplate = false;")
    
    for db in $dbs; do
        echo "Database: $db"
        local old_functions=$(run_sql $OLD_HOST "SELECT proname FROM pg_proc WHERE pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public');" "$db")
        local new_functions=$(run_sql_new $NEW_HOST "SELECT proname FROM pg_proc WHERE pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public');" "$db")
        
        if [ "$old_functions" == "$new_functions" ]; then
            echo -e "  \e[32mMATCH\e[0m Function list matches"
        else
            echo -e "  \e[31mMISMATCH\e[0m Function list mismatch"
            echo "  Only in old instance: $(comm -23 <(echo "$old_functions" | tr ' ' '\n' | sort) <(echo "$new_functions" | tr ' ' '\n' | sort))"
            echo "  Only in new instance: $(comm -13 <(echo "$old_functions" | tr ' ' '\n' | sort) <(echo "$new_functions" | tr ' ' '\n' | sort))"
        fi
    done
}

# Compare sequences
compare_sequences() {
    echo "Comparing sequences..."
    local dbs=$(run_sql $OLD_HOST "SELECT datname FROM pg_database WHERE datistemplate = false;")
    
    for db in $dbs; do
        echo "Database: $db"
        local old_sequences=$(run_sql $OLD_HOST "SELECT sequence_name,start_value, increment, maximum_value, minimum_value FROM information_schema.sequences WHERE sequence_schema = 'public';" "$db")
        local new_sequences=$(run_sql_new $NEW_HOST "SELECT sequence_name, start_value, increment, maximum_value, minimum_value FROM information_schema.sequences WHERE sequence_schema = 'public';" "$db")
        
        if [ "$old_sequences" = "$new_sequences" ]; then
            echo -e "  \e[32mMATCH\e[0m Sequences match"
        else
            echo -e "  \e[31mMISMATCH\e[0m Sequence mismatch detected"
            
            local old_seq_names=$(echo "$old_sequences" | awk '{print $1}')
            local new_seq_names=$(echo "$new_sequences" | awk '{print $1}')
            
            echo "  Sequences only in old instance:"
            comm -23 <(echo "$old_seq_names" | sort) <(echo "$new_seq_names" | sort)
            
            echo "  Sequences only in new instance:"
            comm -13 <(echo "$old_seq_names" | sort) <(echo "$new_seq_names" | sort)
            
            echo "  Sequences with different properties:"
            while IFS='|' read -r name start_value increment maximum_value minimum_value; do
                local new_seq=$(echo "$new_sequences" | grep "^$name|")
                if [ "$new_seq" != "$name|$start_value|$increment|$maximum_value|$minimum_value" ] && [ -n "$new_seq" ]; then
                    echo "    Sequence: $name"
                    echo "      Old: start_value=$start_value, increment=$increment, maximum_value=$maximum_value, minimum_value=$minimum_value"
                    echo "      New: $(echo $new_seq | awk -F'|' '{print "start_value="$2", increment="$3", maximum_value="$4", minimum_value="$5"}')"
                fi
            done <<< "$old_sequences"
        fi
    done
}

# Compare views
compare_views() {
    echo "Comparing views..."
    local dbs=$(run_sql $OLD_HOST "SELECT datname FROM pg_database WHERE datistemplate = false;")
    
    for db in $dbs; do
        echo "Database: $db"
        local old_views=$(run_sql $OLD_HOST "SELECT viewname, definition FROM pg_views WHERE schemaname = 'public';" "$db")
        local new_views=$(run_sql_new $NEW_HOST "SELECT viewname, definition FROM pg_views WHERE schemaname = 'public';" "$db")
        
        if [ "$old_views" = "$new_views" ]; then
            echo -e "  \e[32mMATCH\e[0m  Views match"
        else
            echo -e "  \e[31mMISMATCH\e[0m View mismatch detected"
            local old_view_names=$(echo "$old_views" | awk '{print $1}')
            local new_view_names=$(echo "$new_views" | awk '{print $1}')
            
            echo "  Views only in old instance:"
            comm -23 <(echo "$old_view_names" | sort) <(echo "$new_view_names" | sort)
            
            echo "  Views only in new instance:"
            comm -13 <(echo "$old_view_names" | sort) <(echo "$new_view_names" | sort)
            
            echo "  Views with different definitions:"
            while IFS='|' read -r name definition; do
                local new_def=$(echo "$new_views" | grep "^$name|" | cut -d'|' -f2-)
                if [ "$new_def" != "$definition" ] && [ -n "$new_def" ]; then
                    echo "    View: $name"
                    echo "      Old definition: $definition"
                    echo "      New definition: $new_def"
                fi
            done <<< "$old_views"
        fi
    done
}

# Compare triggers
compare_triggers() {
    echo "Comparing triggers..."
    local dbs=$(run_sql $OLD_HOST "SELECT datname FROM pg_database WHERE datistemplate = false;")
    
    for db in $dbs; do
        echo "Database: $db"
        local old_triggers=$(run_sql $OLD_HOST "SELECT tgname, tgrelid::regclass, pg_get_triggerdef(oid) FROM pg_trigger WHERE tgisinternal = false;" "$db")
        local new_triggers=$(run_sql_new $NEW_HOST "SELECT tgname, tgrelid::regclass, pg_get_triggerdef(oid) FROM pg_trigger WHERE tgisinternal = false;" "$db")
        
        if [ "$old_triggers" = "$new_triggers" ]; then
            echo -e "  \e[32mMATCH\e[0m Triggers match"
        else
            echo -e "  \e[31mMISMATCH\e[0m Trigger mismatch detected"
            local old_trigger_names=$(echo "$old_triggers" | awk '{print $1}')
            local new_trigger_names=$(echo "$new_triggers" | awk '{print $1}')
            
            echo "  Triggers only in old instance:"
            comm -23 <(echo "$old_trigger_names" | sort) <(echo "$new_trigger_names" | sort)
            
            echo "  Triggers only in new instance:"
            comm -13 <(echo "$old_trigger_names" | sort) <(echo "$new_trigger_names" | sort)
            
            echo "  Triggers with different definitions:"
            while IFS='|' read -r name table definition; do
                local new_def=$(echo "$new_triggers" | grep "^$name|$table|" | cut -d'|' -f3-)
                if [ "$new_def" != "$definition" ] && [ -n "$new_def" ]; then
                    echo "    Trigger: $name on $table"
                    echo "      Old definition: $definition"
                    echo "      New definition: $new_def"
                fi
            done <<< "$old_triggers"
        fi
    done
}

# Compare constraints
compare_constraints() {
    echo "Comparing constraints..."
    local dbs=$(run_sql $OLD_HOST "SELECT datname FROM pg_database WHERE datistemplate = false;")
    
    for db in $dbs; do
        echo "Database: $db"
        local old_constraints=$(run_sql $OLD_HOST "SELECT conname, contype, conrelid::regclass, pg_get_constraintdef(oid) FROM pg_constraint WHERE connamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public');" "$db")
        local new_constraints=$(run_sql_new $NEW_HOST "SELECT conname, contype, conrelid::regclass, pg_get_constraintdef(oid) FROM pg_constraint WHERE connamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public');" "$db")
        
        if [ "$old_constraints" = "$new_constraints" ]; then
            echo -e "  \e[32mMATCH\e[0m Constraints match"
        else
            echo -e "  \e[31mMISMATCH\e[0m Constraint mismatch detected"
            local old_constraint_names=$(echo "$old_constraints" | awk '{print $1}')
            local new_constraint_names=$(echo "$new_constraints" | awk '{print $1}')
            
            echo "  Constraints only in old instance:"
            comm -23 <(echo "$old_constraint_names" | sort) <(echo "$new_constraint_names" | sort)
            
            echo "  Constraints only in new instance:"
            comm -13 <(echo "$old_constraint_names" | sort) <(echo "$new_constraint_names" | sort)
            
            echo "  Constraints with different definitions:"
            while IFS='|' read -r name type table definition; do
                local new_def=$(echo "$new_constraints" | grep "^$name|$type|$table|" | cut -d'|' -f4-)
                if [ "$new_def" != "$definition" ] && [ -n "$new_def" ]; then
                    echo "    Constraint: $name ($type) on $table"
                    echo "      Old definition: $definition"
                    echo "      New definition: $new_def"
                fi
            done <<< "$old_constraints"
        fi
    done
}

# Main execution
echo "Starting PostgreSQL instance comparison..."
compare_databases
compare_tables_and_rows
compare_indexes
compare_functions
compare_sequences
compare_views
compare_triggers
compare_constraints
echo "Comparison completed."