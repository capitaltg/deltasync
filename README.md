# Project Delta
Project Delta helps keep multiple LDAP servers synchronized

## Requirements
- To get setup, you need to have JDK8 (or later) installed on your machine.
- For development on Windows, the instructions below were written for a bash
  environment.  If you aren't familiar with it yet,  
  [gitbash](https://git-scm.com/downloads) is an excellent development tool
  to use if you are stuck with Windows.

## Development Setup
You should be able to clone this project and get setup quickly.  You can setup the project for eclipse
development with these steps:

1. Run `./gradlew eclipse` to setup the project for import into eclipse. 
2. Get some ldap running locally. OpenDJ is fairly easy to get setup.
3. Update delta.properties to point the source/destination as needed

## Process Overview
### Modes
* Full sync
```
	For each search result in source data system
   		where objectClass=XYZ
    	where DN within search base
    Find match in target
    	determine if objects are identical
    		compare attributes (limited by configuration)
    	if identical
    		ignore
    	if not identical
    		update	
	Sync group membership

* Partial sync
	Same as above.  Add filter based on time
